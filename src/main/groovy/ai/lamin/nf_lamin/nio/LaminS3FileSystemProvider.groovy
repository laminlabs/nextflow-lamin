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

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.function.Supplier
import java.util.stream.Stream
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

import nextflow.file.CopyOptions
import nextflow.file.FileSystemTransferAware

import ai.lamin.nf_lamin.hub.CloudAccessResponse

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.s3.model.CommonPrefix
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object

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
     * Get or create an S3 file system for the given storage root.
     *
     * Credentials from LaminHub are scoped to a specific storage root (e.g.
     * {@code s3://lamin-us-east-1/JwMEKs04D9WJ}), not to the entire bucket. Multiple
     * storage roots may share the same bucket but require separate credentials. The cache
     * is therefore keyed by {@code storageRoot}.
     *
     * The S3 client asks {@code credentials} for the current STS credentials on every request,
     * so the file system never needs to be recreated when the token is refreshed.
     *
     * @param storageRoot The full storage root URI (e.g. {@code s3://bucket/prefix}), used as cache key and to derive the bucket name
     * @param credentials Source of the current cloud access for this storage root
     * @param target      The publish target being resolved, if any
     * @return The LaminS3FileSystem for this storageRoot
     */
    LaminS3FileSystem getOrCreateFileSystem(String storageRoot, Supplier<CloudAccessResponse> credentials, LaminStorageTarget target = null) {
        synchronized (fileSystems) {
            LaminS3FileSystem existing = fileSystems.get(storageRoot)
            if (existing != null) {
                if (target != null && existing.target == null) {
                    existing.target = target
                }
                return existing
            }

            CloudAccessResponse access = credentials.get()
            AwsS3Client s3Client = createS3Client(new LaminCloudCredentialsProvider(storageRoot, credentials), target?.region)

            LaminS3FileSystem fs = new LaminS3FileSystem(this, storageRoot, s3Client, access?.role, target)
            fileSystems.put(storageRoot, fs)
            log.debug "Created LaminS3FileSystem for storageRoot '${storageRoot}' (role: ${access?.role})"
            return fs
        }
    }

    void removeFileSystem(String storageRoot) {
        fileSystems.remove(storageRoot)
    }

    /**
     * Creates an AWS S3 client. Protected to allow test subclasses to inject mock clients.
     *
     * @param credentials Provider the client asks for credentials on every request
     * @param region      Region of the bucket, or null to start from us-east-1 and follow redirects
     */
    protected AwsS3Client createS3Client(AwsCredentialsProvider credentials, String region) {
        return AwsS3Client.builder()
            .crossRegionAccessEnabled(true)
            .region(region ? Region.of(region) : Region.US_EAST_1)
            .credentialsProvider(credentials)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    }

    // ==================== FileSystemProvider Core Methods ====================

    @Override
    FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        String storageRoot = env.get('storageRoot') as String ?: uri.toString()
        CloudAccessResponse access = new CloudAccessResponse([
            Credentials: [
                AccessKeyId: env.get('accessKeyId'),
                SecretAccessKey: env.get('secretAccessKey'),
                SessionToken: env.get('sessionToken'),
            ],
            StorageAccessibility: [storageRoot: storageRoot, role: env.get('role')],
        ] as Map<String, Object>)
        return getOrCreateFileSystem(storageRoot, { -> access } as Supplier<CloudAccessResponse>)
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

    /** Uploads local files; exposed so tests can lower the multipart threshold. */
    final LaminS3Uploader uploader = new LaminS3Uploader()

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

    /**
     * Open an object for writing.
     *
     * S3 has neither append nor a streaming PUT of unknown length, so the bytes are written to
     * a temp file and uploaded when the stream is closed, see {@link #newByteChannel}.
     */
    @Override
    OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        Set<OpenOption> opts = options ? new HashSet<OpenOption>(options.toList()) : defaultWriteOptions()
        if (opts.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("READ not allowed")
        }
        if (!opts.contains(StandardOpenOption.APPEND)) {
            opts.add(StandardOpenOption.WRITE)
        }
        return Channels.newOutputStream(newByteChannel(path, opts))
    }

    /**
     * Open an object as a channel backed by a local temp file.
     *
     * For writing, the object is downloaded first unless it is truncated anyway, and uploaded
     * back when the channel is closed. This is what the workflow output index file needs: it
     * is written with APPEND, one record at a time.
     */
    @Override
    SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        LaminS3FileSystem fs = (LaminS3FileSystem) s3Path.fileSystem
        boolean append = options.contains(StandardOpenOption.APPEND)
        boolean write = append || options.contains(StandardOpenOption.WRITE)

        Path tmp = Files.createTempFile('lamin-s3-', '.tmp')
        try {
            if (!write) {
                if (!objectExists(s3Path)) {
                    throw new NoSuchFileException(path.toString())
                }
                downloadTo(s3Path, tmp)
                return new TempFileChannel(Files.newByteChannel(tmp, StandardOpenOption.READ), tmp)
            }

            if (fs.isReadOnly()) {
                throw new ReadOnlyFileSystemException()
            }
            boolean exists = objectExists(s3Path)
            if (exists && options.contains(StandardOpenOption.CREATE_NEW)) {
                throw new FileAlreadyExistsException(path.toString())
            }
            if (!exists && !options.contains(StandardOpenOption.CREATE) && !options.contains(StandardOpenOption.CREATE_NEW)) {
                throw new NoSuchFileException(path.toString())
            }
            if (exists && !options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
                downloadTo(s3Path, tmp)
            }

            Set<OpenOption> localOptions = [StandardOpenOption.WRITE] as Set<OpenOption>
            if (append) {
                localOptions.add(StandardOpenOption.APPEND)
            }
            else if (options.contains(StandardOpenOption.READ)) {
                localOptions.add(StandardOpenOption.READ)
            }
            SeekableByteChannel local = Files.newByteChannel(tmp, localOptions)
            return new TempFileChannel(local, tmp) {
                @Override
                protected void onClose() throws IOException {
                    uploader.upload(fs.s3Client, s3Path.bucket, s3Path.key, tmp)
                }
            }
        }
        catch (Exception e) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(dir)
        LaminS3FileSystem fs = (LaminS3FileSystem) s3Path.fileSystem
        String prefix = s3Path.key ? "${s3Path.key}/" : ''

        List<Path> entries = []
        String continuationToken = null
        try {
            while (true) {
                ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(s3Path.bucket)
                    .prefix(prefix)
                    .delimiter('/')
                    .continuationToken(continuationToken)
                    .build()
                ListObjectsV2Response response = fs.s3Client.listObjectsV2(request)
                for (S3Object object : response.contents()) {
                    if (object.key() != prefix) {
                        entries.add(new LaminS3Path(fs, object.key()))
                    }
                }
                for (CommonPrefix common : response.commonPrefixes()) {
                    entries.add(new LaminS3Path(fs, common.prefix().replaceFirst('/$', '')))
                }
                if (!response.isTruncated()) {
                    break
                }
                continuationToken = response.nextContinuationToken()
            }
        }
        catch (Exception e) {
            throw new IOException("Failed to list ${dir}", e)
        }

        List<Path> accepted = entries.findAll { Path p -> filter == null || filter.accept(p) }
        return new ListDirectoryStream(accepted)
    }

    /**
     * A no-op: S3 has no directories, and zero-byte markers would litter Lamin-managed storage
     * with objects LaminDB never created.
     */
    @Override
    void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(dir)
        if (s3Path.fileSystem.isReadOnly()) {
            throw new ReadOnlyFileSystemException()
        }
    }

    @Override
    void delete(Path path) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        LaminS3FileSystem fs = (LaminS3FileSystem) s3Path.fileSystem
        if (fs.isReadOnly()) {
            throw new ReadOnlyFileSystemException()
        }
        if (!objectExists(s3Path)) {
            if (hasChildren(s3Path)) {
                throw new DirectoryNotEmptyException(path.toString())
            }
            throw new NoSuchFileException(path.toString())
        }
        try {
            fs.s3Client.deleteObject(DeleteObjectRequest.builder().bucket(s3Path.bucket).key(s3Path.key).build())
        }
        catch (Exception e) {
            throw new IOException("Failed to delete ${path}", e)
        }
    }

    @Override
    void copy(Path source, Path target, CopyOption... options) throws IOException {
        CopyOptions opts = CopyOptions.parse(options)

        // into this provider: a server-side copy within the same bucket, an upload otherwise
        if (target instanceof LaminS3Path) {
            LaminS3Path s3Target = (LaminS3Path) target
            if (s3Target.fileSystem.isReadOnly()) {
                throw new ReadOnlyFileSystemException()
            }
            if (!opts.replaceExisting() && objectExists(s3Target)) {
                throw new FileAlreadyExistsException(target.toString())
            }
            if (source instanceof LaminS3Path && ((LaminS3Path) source).bucket == s3Target.bucket) {
                LaminS3Path s3Source = (LaminS3Path) source
                try {
                    ((LaminS3FileSystem) s3Target.fileSystem).s3Client.copyObject(
                        CopyObjectRequest.builder()
                            .sourceBucket(s3Source.bucket).sourceKey(s3Source.key)
                            .destinationBucket(s3Target.bucket).destinationKey(s3Target.key)
                            .build()
                    )
                }
                catch (NoSuchKeyException e) {
                    throw new NoSuchFileException(source.toString())
                }
                catch (Exception e) {
                    throw new IOException("Failed to copy ${source} to ${target}", e)
                }
                return
            }
            Files.newInputStream(source).withCloseable { InputStream input ->
                newOutputStream(target).withCloseable { OutputStream output ->
                    output << input
                }
            }
            return
        }

        // out of this provider
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
        copy(source, target, options)
        Files.delete(source)
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

    /**
     * An object exists when HeadObject succeeds; a prefix exists when there are objects under
     * it; the bucket root always exists.
     */
    @Override
    void checkAccess(Path path, AccessMode... modes) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        if (modes.contains(AccessMode.WRITE) && s3Path.fileSystem.isReadOnly()) {
            throw new AccessDeniedException(path.toString(), null, 'LaminHub granted read-only access')
        }
        if (!s3Path.key) {
            return
        }
        if (!objectExists(s3Path) && !hasChildren(s3Path)) {
            throw new NoSuchFileException(path.toString())
        }
    }

    @Override
    <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null
    }

    @Override
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        LaminS3Path s3Path = toLaminS3Path(path)
        if (!s3Path.key) {
            return (A) LaminS3FileAttributes.directory()
        }
        HeadObjectResponse response = headObject(s3Path)
        if (response != null) {
            return (A) new LaminS3FileAttributes(response)
        }
        if (hasChildren(s3Path)) {
            return (A) LaminS3FileAttributes.directory()
        }
        throw new NoSuchFileException(path.toString())
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
        return isLocalFileSystem(source) && target instanceof LaminS3Path
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
        log.debug "upload: ${localFile} -> ${remoteDestination}"
        LaminS3Path s3Path = toLaminS3Path(remoteDestination)
        LaminS3FileSystem fs = (LaminS3FileSystem) s3Path.fileSystem
        if (fs.isReadOnly()) {
            throw new ReadOnlyFileSystemException()
        }

        if (Files.isDirectory(localFile)) {
            Files.walk(localFile).withCloseable { Stream<Path> files ->
                files.filter { Path p -> Files.isRegularFile(p) }.forEach { Path file ->
                    String relative = localFile.relativize(file).toString()
                    LaminS3Path target = (LaminS3Path) s3Path.resolve(relative)
                    uploader.upload(fs.s3Client, target.bucket, target.key, file)
                }
            }
            return
        }
        uploader.upload(fs.s3Client, s3Path.bucket, s3Path.key, localFile)
    }

    // ==================== S3 lookups ====================

    private static Set<OpenOption> defaultWriteOptions() {
        return [StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE] as Set<OpenOption>
    }

    private static HeadObjectResponse headObject(LaminS3Path s3Path) throws IOException {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            return ((LaminS3FileSystem) s3Path.fileSystem).s3Client.headObject(request)
        } catch (NoSuchKeyException e) {
            return null
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null
            }
            throw new AccessDeniedException(s3Path.toString(), null, e.message)
        } catch (Exception e) {
            throw new IOException("Failed to look up ${s3Path}", e)
        }
    }

    private static boolean objectExists(LaminS3Path s3Path) throws IOException {
        return headObject(s3Path) != null
    }

    private static boolean hasChildren(LaminS3Path s3Path) throws IOException {
        String prefix = s3Path.key ? "${s3Path.key}/" : ''
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(s3Path.bucket)
                .prefix(prefix)
                .maxKeys(1)
                .build()
            ListObjectsV2Response response = ((LaminS3FileSystem) s3Path.fileSystem).s3Client.listObjectsV2(request)
            return response.hasContents() && !response.contents().isEmpty()
        } catch (Exception e) {
            throw new IOException("Failed to list ${s3Path}", e)
        }
    }

    private static void downloadTo(LaminS3Path s3Path, Path local) throws IOException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Path.bucket)
                .key(s3Path.key)
                .build()
            ((LaminS3FileSystem) s3Path.fileSystem).s3Client.getObject(request).withCloseable { InputStream input ->
                Files.copy(input, local, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(s3Path.toString())
        }
    }

    /**
     * A channel over a temp file that is deleted on close, after {@link #onClose} ran.
     */
    @CompileStatic
    private static class TempFileChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate
        private final Path tmp
        private boolean closed = false

        TempFileChannel(SeekableByteChannel delegate, Path tmp) {
            this.delegate = delegate
            this.tmp = tmp
        }

        protected void onClose() throws IOException { }

        @Override
        int read(ByteBuffer dst) throws IOException { return delegate.read(dst) }

        @Override
        int write(ByteBuffer src) throws IOException { return delegate.write(src) }

        @Override
        long position() throws IOException { return delegate.position() }

        @Override
        SeekableByteChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this }

        @Override
        long size() throws IOException { return delegate.size() }

        @Override
        SeekableByteChannel truncate(long size) throws IOException { delegate.truncate(size); return this }

        @Override
        boolean isOpen() { return !closed }

        @Override
        void close() throws IOException {
            if (closed) return
            closed = true
            try {
                delegate.close()
                onClose()
            }
            finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    /**
     * A directory stream over an already-fetched list of entries.
     */
    @CompileStatic
    private static class ListDirectoryStream implements DirectoryStream<Path> {
        private final List<Path> entries

        ListDirectoryStream(List<Path> entries) {
            this.entries = entries
        }

        @Override
        Iterator<Path> iterator() { return entries.iterator() }

        @Override
        void close() throws IOException { }
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
