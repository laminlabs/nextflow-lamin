/*
 * Copyright 2025, Lamin Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.lamin.nf_lamin.nio

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

import nextflow.file.CopyOptions
import nextflow.file.FileSystemTransferAware

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * FileSystemProvider for lamin-s3:// URIs.
 *
 * Provides access to S3 objects using temporary session credentials (AccessKeyId +
 * SecretAccessKey + SessionToken) obtained from LaminHub via {@code getCloudAccess()}.
 *
 * URIs are of the form: {@code lamin-s3://bucket-name/path/to/object}
 *
 * This provider is separate from the nf-amazon S3 provider so that temporary session
 * credentials (including a SessionToken) can be used without interfering with the user's
 * existing AWS configuration.
 *
 * Credential federation flow:
 * 1. LaminFileSystemProvider resolves a lamin:// URI to get storageRoot + artifactKey
 * 2. It calls LaminHub.getCloudAccess(storageRoot) to get temporary STS credentials
 * 3. It calls LaminS3FileSystemProvider.getOrCreateFileSystem(bucket, creds...)
 * 4. Nextflow stages the file via lamin-s3://bucket/key using those credentials
 */
@Slf4j
@CompileStatic
class LaminS3FileSystemProvider extends FileSystemProvider implements FileSystemTransferAware {

    static final String SCHEME = 'lamin-s3'

    // Cache: storageRoot -> file system (keyed by storageRoot; credentials are scoped per storageRoot)
    private final Map<String, LaminS3FileSystem> fileSystems = Collections.synchronizedMap(new LinkedHashMap<String, LaminS3FileSystem>())

    @Override
    String getScheme() {
        return SCHEME
    }

    /**
     * Get or create an S3 file system for the given storage root using temporary session credentials.
     *
     * Credentials from LaminHub are scoped to a specific storage root (e.g.
     * {@code s3://lamin-us-east-1/JwMEKs04D9WJ}), not to the entire bucket. Multiple
     * storage roots may share the same bucket but require separate credentials. The cache
     * is therefore keyed by {@code storageRoot}.
     *
     * If a file system already exists for this storageRoot and was created with the same
     * AccessKeyId, it is returned as-is. Otherwise a new S3 client is created with
     * the provided session credentials and a new file system is installed.
     *
     * @param storageRoot The full storage root URI (e.g. {@code s3://bucket/prefix}), used as cache key and to derive the bucket name
     * @param accessKeyId     Temporary access key ID from STS
     * @param secretAccessKey Temporary secret access key from STS
     * @param sessionToken    Temporary session token from STS
     * @param role            The role LaminHub granted on the storage root (read, write or admin)
     * @param target          The publish target being resolved, if any
     * @return The LaminS3FileSystem for this storageRoot
     */
    LaminS3FileSystem getOrCreateFileSystem(String storageRoot, String accessKeyId, String secretAccessKey, String sessionToken,
                                            String role = null, LaminStorageTarget target = null) {
        synchronized (fileSystems) {
            LaminS3FileSystem existing = fileSystems.get(storageRoot)
            if (existing != null && existing.accessKeyId == accessKeyId) {
                if (target != null && existing.target == null) {
                    existing.target = target
                }
                return existing
            }

            // Create a new S3 client with the temporary session credentials
            AwsS3Client s3Client = createS3Client(accessKeyId, secretAccessKey, sessionToken)

            LaminS3FileSystem fs = new LaminS3FileSystem(this, storageRoot, s3Client, accessKeyId, role, target ?: existing?.target)
            fileSystems.put(storageRoot, fs)
            log.debug "Created LaminS3FileSystem for storageRoot '${storageRoot}' with accessKeyId ending in '${accessKeyId.takeRight(4)}'"
            return fs
        }
    }

    void removeFileSystem(String storageRoot) {
        fileSystems.remove(storageRoot)
    }

    /**
     * Creates an AWS S3 client with the given temporary session credentials.
     * Protected to allow test subclasses to inject mock clients.
     */
    protected AwsS3Client createS3Client(String accessKeyId, String secretAccessKey, String sessionToken) {
        AwsSessionCredentials credentials = AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
        return AwsS3Client.builder()
            .crossRegionAccessEnabled(true)
            .region(Region.US_EAST_1)  // default; crossRegionAccessEnabled handles the rest
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    }

    // ==================== FileSystemProvider Core Methods ====================

    @Override
    FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        String storageRoot = env.get('storageRoot') as String ?: uri.toString()
        String accessKeyId = env.get('accessKeyId') as String
        String secretAccessKey = env.get('secretAccessKey') as String
        String sessionToken = env.get('sessionToken') as String
        String role = env.get('role') as String
        return getOrCreateFileSystem(storageRoot, accessKeyId, secretAccessKey, sessionToken, role)
    }

    @Override
    FileSystem getFileSystem(URI uri) {
        String bucket = uri.host
        // The cache is keyed by storageRoot; scan for a matching bucket
        LaminS3FileSystem fs = (LaminS3FileSystem) fileSystems.values().find { it.bucketName == bucket }
        if (fs == null) {
            throw new FileSystemNotFoundException("No lamin-s3 file system for bucket: ${bucket}")
        }
        return fs
    }

    @Override
    Path getPath(URI uri) {
        String bucket = uri.host
        // The cache is keyed by storageRoot; scan for a matching bucket
        LaminS3FileSystem fs = (LaminS3FileSystem) fileSystems.values().find { it.bucketName == bucket }
        if (fs == null) {
            throw new FileSystemNotFoundException("No lamin-s3 file system for bucket '${bucket}'. Call newFileSystem() or getOrCreateFileSystem() first.")
        }
        String key = uri.path?.replaceFirst('^/', '') ?: ''
        return new LaminS3Path(fs, key)
    }

    // ==================== File Operations ====================

    @Override
    InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            return ((LaminS3FileSystem) s3Path.fileSystem).s3Client.getObject(request)
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(path.toString())
        } catch (Exception e) {
            throw new IOException("Failed to open input stream for ${path}", e)
        }
    }

    @Override
    OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        throw new UnsupportedOperationException("Writing to ${SCHEME}:// paths is not supported")
    }

    @Override
    SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        throw new UnsupportedOperationException("SeekableByteChannel not supported for ${SCHEME}:// paths")
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new UnsupportedOperationException("Directory listing not supported for ${SCHEME}:// paths")
    }

    @Override
    void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        throw new UnsupportedOperationException("Creating directories in ${SCHEME}:// paths is not supported")
    }

    @Override
    void delete(Path path) throws IOException {
        throw new UnsupportedOperationException("Deleting ${SCHEME}:// paths is not supported")
    }

    @Override
    void copy(Path source, Path target, CopyOption... options) throws IOException {
        LaminS3Path s3Source = toLaminS3Path(source)
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Source.bucket)
                .key(s3Source.key)
                .build()
            InputStream inputStream = ((LaminS3FileSystem) s3Source.fileSystem).s3Client.getObject(request)
            try {
                Files.copy(inputStream, target, options)
            } finally {
                inputStream.close()
            }
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(source.toString())
        }
    }

    @Override
    void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("Moving ${SCHEME}:// paths is not supported")
    }

    @Override
    boolean isSameFile(Path path1, Path path2) throws IOException {
        return path1.equals(path2)
    }

    @Override
    boolean isHidden(Path path) throws IOException {
        return false
    }

    @Override
    FileStore getFileStore(Path path) throws IOException {
        throw new UnsupportedOperationException("FileStore not supported for ${SCHEME}:// paths")
    }

    @Override
    void checkAccess(Path path, AccessMode... modes) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            ((LaminS3FileSystem) s3Path.fileSystem).s3Client.headObject(request)
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(path.toString())
        } catch (Exception e) {
            throw new AccessDeniedException(path.toString(), null, e.message)
        }
    }

    @Override
    <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null
    }

    @Override
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            HeadObjectResponse response = ((LaminS3FileSystem) s3Path.fileSystem).s3Client.headObject(request)
            return (A) new LaminS3FileAttributes(response)
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(path.toString())
        } catch (Exception e) {
            throw new IOException("Failed to read attributes for ${path}", e)
        }
    }

    @Override
    Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return Collections.emptyMap()
    }

    @Override
    void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("Setting attributes on ${SCHEME}:// paths is not supported")
    }

    // ==================== FileSystemTransferAware ====================

    @Override
    boolean canUpload(Path source, Path target) {
        return false
    }

    @Override
    boolean canDownload(Path source, Path target) {
        return source instanceof LaminS3Path && isLocalFileSystem(target)
    }

    @Override
    void download(Path remoteFile, Path localDestination, CopyOption... options) throws IOException {
        log.debug "download: ${remoteFile} -> ${localDestination}"
        LaminS3Path s3Path = toLaminS3Path(remoteFile)

        CopyOptions opts = CopyOptions.parse(options)
        if (opts.replaceExisting()) {
            Files.deleteIfExists(localDestination)
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            InputStream inputStream = ((LaminS3FileSystem) s3Path.fileSystem).s3Client.getObject(request)
            try {
                Files.copy(inputStream, localDestination)
            } finally {
                inputStream.close()
            }
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(remoteFile.toString())
        }
    }

    @Override
    void upload(Path localFile, Path remoteDestination, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("Uploading to ${SCHEME}:// paths is not supported")
    }

    // ==================== Helpers ====================

    private static LaminS3Path toLaminS3Path(Path path) {
        if (path instanceof LaminS3Path) {
            return (LaminS3Path) path
        }
        throw new ProviderMismatchException("Not a LaminS3Path: ${path?.class?.name}")
    }

    private static boolean isLocalFileSystem(Path path) {
        return path.fileSystem == java.nio.file.FileSystems.getDefault()
    }
}
