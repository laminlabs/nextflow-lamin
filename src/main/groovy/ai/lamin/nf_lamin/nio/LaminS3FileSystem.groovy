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

import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

import software.amazon.awssdk.services.s3.S3Client as AwsS3Client

/**
 * FileSystem for lamin-s3:// URIs.
 *
 * Holds an AWS S3 client configured with temporary session credentials obtained
 * from LaminHub for a specific S3 bucket. This allows Nextflow to access LaminDB
 * artifacts stored in Lamin-managed S3 buckets without sharing credentials with
 * the global Nextflow AWS configuration.
 */
@Slf4j
@CompileStatic
final class LaminS3FileSystem extends FileSystem {

    /** Roles for which LaminHub grants PutObject and DeleteObject. */
    static final List<String> WRITE_ROLES = ['write', 'admin']

    private final LaminS3FileSystemProvider provider
    private final String storageRoot
    private final AwsS3Client s3Client
    // Track which access key this filesystem was created with, for cache invalidation
    final String accessKeyId
    /** The role LaminHub granted on the storage root: read, write or admin */
    final String role
    /** The publish target this filesystem was resolved for, if any */
    volatile LaminStorageTarget target

    private volatile boolean closed = false

    LaminS3FileSystem(LaminS3FileSystemProvider provider, String storageRoot, AwsS3Client s3Client, String accessKeyId,
                      String role = null, LaminStorageTarget target = null) {
        this.provider = provider
        this.storageRoot = storageRoot
        this.s3Client = s3Client
        this.accessKeyId = accessKeyId
        this.role = role
        this.target = target
        log.debug "Created LaminS3FileSystem for storageRoot: ${storageRoot} (role: ${role})"
    }

    String getStorageRoot() {
        return storageRoot
    }

    String getBucketName() {
        return new URI(storageRoot).host
    }

    AwsS3Client getS3Client() {
        return s3Client
    }

    @Override
    FileSystemProvider provider() {
        return provider
    }

    @Override
    void close() throws IOException {
        closed = true
        s3Client.close()
        provider.removeFileSystem(storageRoot)
    }

    @Override
    boolean isOpen() {
        return !closed
    }

    @Override
    boolean isReadOnly() {
        return !WRITE_ROLES.contains(role)
    }

    @Override
    String getSeparator() {
        return '/'
    }

    @Override
    Iterable<Path> getRootDirectories() {
        return Collections.emptyList()
    }

    @Override
    Iterable<FileStore> getFileStores() {
        return Collections.emptyList()
    }

    @Override
    Set<String> supportedFileAttributeViews() {
        return Collections.unmodifiableSet(['basic'] as Set)
    }

    @Override
    Path getPath(String first, String... more) {
        String key = first
        if (more) {
            key = ([first] + more.toList()).join('/')
        }
        return new LaminS3Path(this, key)
    }

    @Override
    PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException()
    }

    @Override
    UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException()
    }

    @Override
    WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException()
    }
}
