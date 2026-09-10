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

import spock.lang.Specification

import software.amazon.awssdk.services.s3.S3Client as AwsS3Client

class LaminS3FileSystemTest extends Specification {

    LaminS3FileSystemProvider provider
    AwsS3Client s3Client
    LaminS3FileSystem fs

    def setup() {
        provider = Mock(LaminS3FileSystemProvider)
        s3Client = Mock(AwsS3Client)
        fs = new LaminS3FileSystem(provider, 's3://my-bucket/prefix', s3Client, 'AKIAIOSFODNN7EXAMPLE')
    }

    // ==================== Properties ====================

    def "getStorageRoot() returns the storageRoot"() {
        expect:
        fs.storageRoot == 's3://my-bucket/prefix'
    }

    def "getBucketName() extracts bucket from storageRoot URI"() {
        expect:
        fs.bucketName == 'my-bucket'
    }

    def "getAccessKeyId() returns accessKeyId"() {
        expect:
        fs.accessKeyId == 'AKIAIOSFODNN7EXAMPLE'
    }

    def "getS3Client() returns the s3Client"() {
        expect:
        fs.s3Client == s3Client
    }

    def "provider() returns the provider"() {
        expect:
        fs.provider() == provider
    }

    def "getBucketName() works for different storageRoots"() {
        given:
        def fs2 = new LaminS3FileSystem(provider, 's3://other-bucket/deep/prefix', s3Client, 'key')

        expect:
        fs2.bucketName == 'other-bucket'
    }

    def "isReadOnly() follows the role LaminHub granted"() {
        expect:
        fs.isReadOnly()
        new LaminS3FileSystem(provider, 's3://b/p', s3Client, 'k', 'read').isReadOnly()
        !new LaminS3FileSystem(provider, 's3://b/p', s3Client, 'k', 'write').isReadOnly()
        !new LaminS3FileSystem(provider, 's3://b/p', s3Client, 'k', 'admin').isReadOnly()
    }

    def "carries the publish target it was created for"() {
        given:
        def target = new LaminStorageTarget(storageRoot: 's3://b/p', storageUid: 'St0rage00001', spaceId: 5)

        when:
        def fs2 = new LaminS3FileSystem(provider, 's3://b/p', s3Client, 'k', 'write', target)

        then:
        fs2.target.is(target)
        fs.target == null
    }

    // ==================== Open / close ====================

    def "isOpen() returns true initially"() {
        expect:
        fs.isOpen()
    }

    def "close() marks the filesystem as closed"() {
        when:
        fs.close()

        then:
        !fs.isOpen()
    }

    def "close() closes the s3Client"() {
        when:
        fs.close()

        then:
        1 * s3Client.close()
    }

    def "close() notifies the provider"() {
        when:
        fs.close()

        then:
        1 * provider.removeFileSystem('s3://my-bucket/prefix')
    }

    // ==================== FileSystem properties ====================

    def "isReadOnly() returns true"() {
        expect:
        fs.isReadOnly()
    }

    def "getSeparator() returns '/'"() {
        expect:
        fs.separator == '/'
    }

    def "getRootDirectories() is empty"() {
        expect:
        fs.rootDirectories.toList().isEmpty()
    }

    def "getFileStores() is empty"() {
        expect:
        fs.fileStores.toList().isEmpty()
    }

    def "supportedFileAttributeViews() contains 'basic'"() {
        expect:
        fs.supportedFileAttributeViews().contains('basic')
    }

    // ==================== getPath ====================

    def "getPath() returns a LaminS3Path with the given key"() {
        when:
        def path = fs.getPath('some/object.txt')

        then:
        path instanceof LaminS3Path
        (path as LaminS3Path).key == 'some/object.txt'
    }

    def "getPath() with multiple parts joins them with '/'"() {
        when:
        def path = fs.getPath('dir', 'subdir', 'file.txt')

        then:
        path instanceof LaminS3Path
        (path as LaminS3Path).key == 'dir/subdir/file.txt'
    }

    def "getPath() with empty key returns root-like path"() {
        when:
        def path = fs.getPath('')

        then:
        path instanceof LaminS3Path
        (path as LaminS3Path).key == ''
    }

    // ==================== Unsupported operations ====================

    def "getPathMatcher() throws UnsupportedOperationException"() {
        when:
        fs.getPathMatcher('glob:*')

        then:
        thrown(UnsupportedOperationException)
    }

    def "getUserPrincipalLookupService() throws UnsupportedOperationException"() {
        when:
        fs.getUserPrincipalLookupService()

        then:
        thrown(UnsupportedOperationException)
    }

    def "newWatchService() throws UnsupportedOperationException"() {
        when:
        fs.newWatchService()

        then:
        thrown(UnsupportedOperationException)
    }
}
