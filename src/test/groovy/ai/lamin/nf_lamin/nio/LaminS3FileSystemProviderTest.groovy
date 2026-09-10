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

import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardOpenOption

import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CommonPrefix
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse

/**
 * Tests for LaminS3FileSystemProvider.
 *
 * A TestableLaminS3FileSystemProvider subclass overrides createS3Client() to inject
 * a mock AwsS3Client, enabling unit tests without real AWS credentials.
 */
class LaminS3FileSystemProviderTest extends Specification {

    /**
     * Test subclass that overrides createS3Client() to return a mock AwsS3Client.
     */
    static class TestableLaminS3FileSystemProvider extends LaminS3FileSystemProvider {
        AwsS3Client injectedClient

        @Override
        protected AwsS3Client createS3Client(String accessKeyId, String secretAccessKey, String sessionToken) {
            return injectedClient
        }
    }

    TestableLaminS3FileSystemProvider provider
    AwsS3Client s3Client

    def setup() {
        s3Client = Mock(AwsS3Client)
        provider = new TestableLaminS3FileSystemProvider(injectedClient: s3Client)
    }

    // ==================== Scheme ====================

    def "getScheme() returns 'lamin-s3'"() {
        expect:
        provider.getScheme() == 'lamin-s3'
    }

    // ==================== getOrCreateFileSystem ====================

    def "getOrCreateFileSystem() creates and returns a new filesystem"() {
        when:
        LaminS3FileSystem fs = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')

        then:
        fs != null
        fs.storageRoot == 's3://bucket/prefix'
        fs.bucketName == 'bucket'
        fs.accessKeyId == 'AKID'
        fs.s3Client == s3Client
    }

    def "getOrCreateFileSystem() returns cached filesystem for same accessKeyId"() {
        given:
        LaminS3FileSystem fs1 = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')

        when:
        LaminS3FileSystem fs2 = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret2', 'token2')

        then:
        fs2.is(fs1)
    }

    def "getOrCreateFileSystem() creates new filesystem when accessKeyId changes"() {
        given:
        LaminS3FileSystem fs1 = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID1', 'secret', 'token')
        AwsS3Client s3Client2 = Mock(AwsS3Client)
        provider.injectedClient = s3Client2

        when:
        LaminS3FileSystem fs2 = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID2', 'secret', 'token')

        then:
        !fs2.is(fs1)
        fs2.accessKeyId == 'AKID2'
        fs2.s3Client == s3Client2
    }

    def "getOrCreateFileSystem() creates separate filesystems for different storageRoots"() {
        when:
        LaminS3FileSystem fs1 = provider.getOrCreateFileSystem('s3://bucket/prefix1', 'AKID', 'secret', 'token')
        LaminS3FileSystem fs2 = provider.getOrCreateFileSystem('s3://bucket/prefix2', 'AKID', 'secret', 'token')

        then:
        !fs2.is(fs1)
        fs1.storageRoot == 's3://bucket/prefix1'
        fs2.storageRoot == 's3://bucket/prefix2'
    }

    // ==================== removeFileSystem ====================

    def "removeFileSystem() removes the filesystem from the cache"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        provider.removeFileSystem('s3://bucket/prefix')

        when:
        provider.getFileSystem(new URI('lamin-s3://bucket/key'))

        then:
        thrown(FileSystemNotFoundException)
    }

    // ==================== getFileSystem ====================

    def "getFileSystem(URI) returns the filesystem matching the bucket"() {
        given:
        LaminS3FileSystem expected = provider.getOrCreateFileSystem('s3://my-bucket/prefix', 'AKID', 'secret', 'token')

        when:
        def fs = provider.getFileSystem(new URI('lamin-s3://my-bucket/any/key'))

        then:
        fs.is(expected)
    }

    def "getFileSystem(URI) throws FileSystemNotFoundException for unknown bucket"() {
        when:
        provider.getFileSystem(new URI('lamin-s3://no-such-bucket/key'))

        then:
        thrown(FileSystemNotFoundException)
    }

    // ==================== getPath ====================

    def "getPath(URI) returns a LaminS3Path for a known bucket"() {
        given:
        provider.getOrCreateFileSystem('s3://my-bucket/prefix', 'AKID', 'secret', 'token')

        when:
        def path = provider.getPath(new URI('lamin-s3://my-bucket/some/object.txt'))

        then:
        path instanceof LaminS3Path
        (path as LaminS3Path).bucket == 'my-bucket'
        (path as LaminS3Path).key == 'some/object.txt'
    }

    def "getPath(URI) throws FileSystemNotFoundException for unknown bucket"() {
        when:
        provider.getPath(new URI('lamin-s3://no-such-bucket/key'))

        then:
        thrown(FileSystemNotFoundException)
    }

    // ==================== newFileSystem ====================

    def "newFileSystem(URI, env) delegates to getOrCreateFileSystem"() {
        given:
        Map<String, Object> env = [
            storageRoot    : 's3://bucket/prefix',
            accessKeyId    : 'AKID',
            secretAccessKey: 'secret',
            sessionToken   : 'token'
        ]

        when:
        def fs = provider.newFileSystem(new URI('lamin-s3://bucket/'), env)

        then:
        fs instanceof LaminS3FileSystem
        (fs as LaminS3FileSystem).storageRoot == 's3://bucket/prefix'
    }

    // ==================== Non-S3 file operations ====================

    def "isSameFile() returns true for equal paths"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p1 = provider.getPath(new URI('lamin-s3://bucket/a/b'))
        def p2 = provider.getPath(new URI('lamin-s3://bucket/a/b'))

        expect:
        provider.isSameFile(p1, p2)
    }

    def "isSameFile() returns false for different paths"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p1 = provider.getPath(new URI('lamin-s3://bucket/a/b'))
        def p2 = provider.getPath(new URI('lamin-s3://bucket/x/y'))

        expect:
        !provider.isSameFile(p1, p2)
    }

    def "isHidden() always returns false"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p = provider.getPath(new URI('lamin-s3://bucket/hidden/.hidden'))

        expect:
        !provider.isHidden(p)
    }

    def "canUpload() is true for a local source and an S3 target"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def s3Path = provider.getPath(new URI('lamin-s3://bucket/a/b'))
        def localPath = java.nio.file.Paths.get('/tmp/local.txt')

        expect:
        provider.canUpload(localPath, s3Path)
        !provider.canUpload(s3Path, localPath)
        !provider.canUpload(localPath, localPath)
    }

    def "canDownload() returns true for S3 source and local target"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def s3Path = provider.getPath(new URI('lamin-s3://bucket/a/b'))
        Path localPath = Files.createTempDirectory('lamin-test').resolve('file.txt')

        expect:
        provider.canDownload(s3Path, localPath)
    }

    def "canDownload() returns false for local→local"() {
        given:
        def local1 = java.nio.file.Paths.get('/tmp/a.txt')
        def local2 = java.nio.file.Paths.get('/tmp/b.txt')

        expect:
        !provider.canDownload(local1, local2)
    }

    // ==================== Unsupported operations ====================

    def "getFileStore() throws UnsupportedOperationException"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p = provider.getPath(new URI('lamin-s3://bucket/k'))

        when:
        provider.getFileStore(p)

        then:
        thrown(UnsupportedOperationException)
    }

    def "setAttribute() throws UnsupportedOperationException"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p = provider.getPath(new URI('lamin-s3://bucket/k'))

        when:
        provider.setAttribute(p, 'custom:attr', 'value')

        then:
        thrown(UnsupportedOperationException)
    }

    // ==================== Attribute views ====================

    def "getFileAttributeView() returns null"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p = provider.getPath(new URI('lamin-s3://bucket/k'))

        expect:
        provider.getFileAttributeView(p, BasicFileAttributes) == null
    }

    def "readAttributes(path, String) returns empty map"() {
        given:
        provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        def p = provider.getPath(new URI('lamin-s3://bucket/k'))

        expect:
        provider.readAttributes(p, 'basic:*').isEmpty()
    }

    // ==================== S3 I/O operations (mocked) ====================

    private LaminS3Path s3Path(String key) {
        LaminS3FileSystem fs = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token')
        return new LaminS3Path(fs, key)
    }

    private static ResponseInputStream<GetObjectResponse> responseStream(byte[] content) {
        return new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(content))
        )
    }

    def "newInputStream() returns an InputStream backed by S3 getObject"() {
        given:
        def p = s3Path('prefix/file.txt')
        byte[] content = 'hello s3'.bytes
        s3Client.getObject(_ as GetObjectRequest) >> responseStream(content)

        when:
        InputStream stream = provider.newInputStream(p)
        byte[] read = stream.bytes

        then:
        read == content
    }

    def "newInputStream() throws NoSuchFileException when key does not exist"() {
        given:
        def p = s3Path('prefix/missing.txt')
        s3Client.getObject(_ as GetObjectRequest) >> { throw NoSuchKeyException.builder().message('not found').statusCode(404).build() }

        when:
        provider.newInputStream(p)

        then:
        thrown(NoSuchFileException)
    }

    def "checkAccess() calls headObject and succeeds when key exists"() {
        given:
        def p = s3Path('prefix/exists.txt')

        when:
        provider.checkAccess(p)

        then:
        1 * s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(42L).build()
        0 * s3Client.listObjectsV2(*_)
    }

    def "checkAccess() throws NoSuchFileException when key does not exist"() {
        given:
        def p = s3Path('prefix/missing.txt')
        nothingExists()

        when:
        provider.checkAccess(p)

        then:
        thrown(NoSuchFileException)
    }

    def "readAttributes(path, Class) returns LaminS3FileAttributes"() {
        given:
        def p = s3Path('prefix/file.txt')
        s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(100L).build()

        when:
        def attrs = provider.readAttributes(p, BasicFileAttributes)

        then:
        attrs instanceof LaminS3FileAttributes
        (attrs as LaminS3FileAttributes).size() == 100L
    }

    def "readAttributes(path, Class) throws NoSuchFileException when key does not exist"() {
        given:
        def p = s3Path('prefix/missing.txt')
        nothingExists()

        when:
        provider.readAttributes(p, BasicFileAttributes)

        then:
        thrown(NoSuchFileException)
    }

    def "copy() copies S3 content to a local file"() {
        given:
        def p = s3Path('prefix/file.txt')
        byte[] content = 'copied content'.bytes
        s3Client.getObject(_ as GetObjectRequest) >> responseStream(content)
        Path tmpDir = Files.createTempDirectory('lamin-copy-test')
        Path target = tmpDir.resolve('output.txt')

        when:
        provider.copy(p, target)

        then:
        Files.readAllBytes(target) == content

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "copy() throws NoSuchFileException when source key does not exist"() {
        given:
        def p = s3Path('prefix/missing.txt')
        s3Client.getObject(_ as GetObjectRequest) >> { throw NoSuchKeyException.builder().message('not found').statusCode(404).build() }
        Path tmpDir = Files.createTempDirectory('lamin-copy-test')
        Path target = tmpDir.resolve('output.txt')

        when:
        provider.copy(p, target)

        then:
        thrown(NoSuchFileException)

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "download() writes S3 content to a local file"() {
        given:
        def p = s3Path('prefix/file.txt')
        byte[] content = 'downloaded content'.bytes
        s3Client.getObject(_ as GetObjectRequest) >> responseStream(content)
        Path tmpDir = Files.createTempDirectory('lamin-download-test')
        Path dest = tmpDir.resolve('output.txt')

        when:
        provider.download(p, dest)

        then:
        Files.readAllBytes(dest) == content

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "download() throws NoSuchFileException when key does not exist"() {
        given:
        def p = s3Path('prefix/missing.txt')
        s3Client.getObject(_ as GetObjectRequest) >> { throw NoSuchKeyException.builder().message('not found').statusCode(404).build() }
        Path tmpDir = Files.createTempDirectory('lamin-download-test')
        Path dest = tmpDir.resolve('output.txt')

        when:
        provider.download(p, dest)

        then:
        thrown(NoSuchFileException)

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "download() with REPLACE_EXISTING replaces existing file"() {
        given:
        def p = s3Path('prefix/file.txt')
        byte[] newContent = 'new content'.bytes
        s3Client.getObject(_ as GetObjectRequest) >> responseStream(newContent)
        Path tmpDir = Files.createTempDirectory('lamin-download-test')
        Path dest = tmpDir.resolve('existing.txt')
        Files.write(dest, 'old content'.bytes)

        when:
        provider.download(p, dest, StandardCopyOption.REPLACE_EXISTING)

        then:
        Files.readAllBytes(dest) == newContent

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    // ==================== Writes ====================

    private static final NoSuchKeyException NO_SUCH_KEY = NoSuchKeyException.builder().message('not found').statusCode(404).build()
    private static final ListObjectsV2Response EMPTY_LISTING = ListObjectsV2Response.builder().build()

    private LaminS3Path writablePath(String key) {
        LaminS3FileSystem fs = provider.getOrCreateFileSystem('s3://bucket/prefix', 'AKID', 'secret', 'token', 'write')
        return new LaminS3Path(fs, key)
    }

    /** Stub the client so that nothing exists under the bucket. */
    private void nothingExists() {
        s3Client.headObject(_ as HeadObjectRequest) >> { throw NO_SUCH_KEY }
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> EMPTY_LISTING
    }

    private static byte[] bytesOf(RequestBody body) {
        return body.contentStreamProvider().newStream().bytes
    }

    def "newOutputStream() uploads the written bytes on close"() {
        given:
        def p = writablePath('prefix/out.txt')
        nothingExists()
        def puts = []
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest r, RequestBody b ->
            puts << [r.bucket(), r.key(), new String(bytesOf(b))]
            PutObjectResponse.builder().build()
        }

        when:
        OutputStream out = provider.newOutputStream(p)
        out.write('hello '.bytes)
        out.write('world'.bytes)

        then:
        puts.isEmpty()

        when:
        out.close()

        then:
        puts == [['bucket', 'prefix/out.txt', 'hello world']]
    }

    def "newOutputStream() with CREATE_NEW refuses to overwrite an existing object"() {
        given:
        def p = writablePath('prefix/out.txt')
        s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(1L).build()

        when:
        provider.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

        then:
        thrown(FileAlreadyExistsException)
        0 * s3Client.putObject(*_)
    }

    def "newOutputStream() with APPEND keeps the existing content"() {
        given:
        def p = writablePath('prefix/index.csv')
        s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(4L).build()
        s3Client.getObject(_ as GetObjectRequest) >> responseStream('abc\n'.bytes)
        def puts = []
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest r, RequestBody b ->
            puts << new String(bytesOf(b))
            PutObjectResponse.builder().build()
        }

        when:
        OutputStream out = provider.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        out.write('def\n'.bytes)
        out.close()

        then:
        puts == ['abc\ndef\n']
    }

    def "newOutputStream() refuses to write to a read-only filesystem"() {
        given:
        def p = s3Path('prefix/out.txt')

        when:
        provider.newOutputStream(p)

        then:
        thrown(ReadOnlyFileSystemException)
    }

    def "newByteChannel() for writing uploads on close"() {
        given:
        def p = writablePath('prefix/out.bin')
        nothingExists()
        def puts = []
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest r, RequestBody b ->
            puts << new String(bytesOf(b))
            PutObjectResponse.builder().build()
        }

        when:
        SeekableByteChannel channel = provider.newByteChannel(p, [StandardOpenOption.CREATE, StandardOpenOption.WRITE] as Set)
        channel.write(ByteBuffer.wrap('channel'.bytes))
        channel.close()

        then:
        puts == ['channel']
    }

    def "newByteChannel() for reading serves the object content"() {
        given:
        def p = s3Path('prefix/in.bin')
        s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(4L).build()
        s3Client.getObject(_ as GetObjectRequest) >> responseStream('data'.bytes)

        when:
        SeekableByteChannel channel = provider.newByteChannel(p, [StandardOpenOption.READ] as Set)
        ByteBuffer buffer = ByteBuffer.allocate(16)
        channel.read(buffer)
        channel.close()

        then:
        new String(buffer.array(), 0, buffer.position()) == 'data'
        0 * s3Client.putObject(*_)
    }

    def "createDirectory() is a no-op"() {
        given:
        def p = writablePath('prefix/dir')

        when:
        provider.createDirectory(p)

        then:
        0 * s3Client._
    }

    def "checkAccess() succeeds for a prefix that has objects under it"() {
        given:
        def p = s3Path('prefix/dir')
        s3Client.headObject(_ as HeadObjectRequest) >> { throw NO_SUCH_KEY }
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> { ListObjectsV2Request r ->
            assert r.prefix() == 'prefix/dir/'
            ListObjectsV2Response.builder().contents(S3Object.builder().key('prefix/dir/a.txt').size(1L).build()).build()
        }

        when:
        provider.checkAccess(p)

        then:
        noExceptionThrown()
    }

    def "checkAccess() succeeds for the bucket root"() {
        when:
        provider.checkAccess(s3Path(''))

        then:
        0 * s3Client._
    }

    def "checkAccess() throws NoSuchFileException when neither object nor prefix exist"() {
        given:
        def p = s3Path('prefix/missing')
        nothingExists()

        when:
        provider.checkAccess(p)

        then:
        thrown(NoSuchFileException)
    }

    def "readAttributes() reports a prefix with objects under it as a directory"() {
        given:
        def p = s3Path('prefix/dir')
        s3Client.headObject(_ as HeadObjectRequest) >> { throw NO_SUCH_KEY }
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> ListObjectsV2Response.builder()
            .contents(S3Object.builder().key('prefix/dir/a.txt').size(1L).build()).build()

        when:
        def attrs = provider.readAttributes(p, BasicFileAttributes)

        then:
        attrs.isDirectory()
        !attrs.isRegularFile()
        attrs.size() == 0L
    }

    def "delete() removes the object"() {
        given:
        def p = writablePath('prefix/old.txt')
        s3Client.headObject(_ as HeadObjectRequest) >> HeadObjectResponse.builder().contentLength(1L).build()

        when:
        provider.delete(p)

        then:
        1 * s3Client.deleteObject({ DeleteObjectRequest r -> r.key() == 'prefix/old.txt' }) >> DeleteObjectResponse.builder().build()
    }

    def "delete() throws NoSuchFileException for a missing object"() {
        given:
        def p = writablePath('prefix/missing.txt')
        nothingExists()

        when:
        provider.delete(p)

        then:
        thrown(NoSuchFileException)
    }

    def "delete() throws DirectoryNotEmptyException for a prefix with objects under it"() {
        given:
        def p = writablePath('prefix/dir')
        s3Client.headObject(_ as HeadObjectRequest) >> { throw NO_SUCH_KEY }
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> ListObjectsV2Response.builder()
            .contents(S3Object.builder().key('prefix/dir/a.txt').size(1L).build()).build()

        when:
        provider.delete(p)

        then:
        thrown(DirectoryNotEmptyException)
        0 * s3Client.deleteObject(*_)
    }

    def "delete() refuses on a read-only filesystem"() {
        when:
        provider.delete(s3Path('prefix/old.txt'))

        then:
        thrown(ReadOnlyFileSystemException)
    }

    def "newDirectoryStream() lists objects and sub-prefixes one level deep"() {
        given:
        def p = s3Path('prefix/dir')
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> { ListObjectsV2Request r ->
            assert r.prefix() == 'prefix/dir/'
            assert r.delimiter() == '/'
            ListObjectsV2Response.builder()
                .contents(S3Object.builder().key('prefix/dir/a.txt').size(1L).build())
                .commonPrefixes(CommonPrefix.builder().prefix('prefix/dir/sub/').build())
                .build()
        }

        when:
        def entries = provider.newDirectoryStream(p, { true }).collect { (it as LaminS3Path).key }

        then:
        entries == ['prefix/dir/a.txt', 'prefix/dir/sub']
    }

    def "newDirectoryStream() applies the filter"() {
        given:
        def p = s3Path('prefix/dir')
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> ListObjectsV2Response.builder()
            .contents(S3Object.builder().key('prefix/dir/a.txt').size(1L).build(),
                      S3Object.builder().key('prefix/dir/b.csv').size(1L).build())
            .build()

        when:
        def entries = provider.newDirectoryStream(p, { Path it -> it.toString().endsWith('.csv') }).collect { (it as LaminS3Path).key }

        then:
        entries == ['prefix/dir/b.csv']
    }

    def "upload() puts a local file"() {
        given:
        def p = writablePath('prefix/uploaded.txt')
        Path tmpDir = Files.createTempDirectory('lamin-upload-test')
        Path local = tmpDir.resolve('local.txt')
        Files.write(local, 'local content'.bytes)
        def puts = []
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest r, RequestBody b ->
            puts << [r.key(), new String(bytesOf(b))]
            PutObjectResponse.builder().build()
        }

        when:
        provider.upload(local, p)

        then:
        puts == [['prefix/uploaded.txt', 'local content']]

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "upload() of a directory puts every file under the target"() {
        given:
        def p = writablePath('prefix/dir')
        Path tmpDir = Files.createTempDirectory('lamin-upload-test')
        Files.write(tmpDir.resolve('a.txt'), 'a'.bytes)
        Files.createDirectories(tmpDir.resolve('sub'))
        Files.write(tmpDir.resolve('sub/b.txt'), 'b'.bytes)
        def keys = []
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest r, RequestBody b ->
            keys << r.key()
            PutObjectResponse.builder().build()
        }

        when:
        provider.upload(tmpDir, p)

        then:
        keys.sort() == ['prefix/dir/a.txt', 'prefix/dir/sub/b.txt']

        cleanup:
        tmpDir.toFile().deleteDir()
    }

    def "copy() between two lamin-s3 paths is a server-side copy"() {
        given:
        def source = writablePath('prefix/a.txt')
        def target = writablePath('prefix/b.txt')
        nothingExists()

        when:
        provider.copy(source, target)

        then:
        1 * s3Client.copyObject({ CopyObjectRequest r ->
            r.sourceBucket() == 'bucket' && r.sourceKey() == 'prefix/a.txt' && r.destinationKey() == 'prefix/b.txt'
        }) >> CopyObjectResponse.builder().build()
    }

    def "move() copies then deletes the source"() {
        given:
        def source = writablePath('prefix/a.txt')
        def target = writablePath('prefix/b.txt')
        s3Client.headObject(_ as HeadObjectRequest) >> { HeadObjectRequest r ->
            if (r.key() == 'prefix/b.txt') throw NO_SUCH_KEY
            HeadObjectResponse.builder().contentLength(1L).build()
        }
        s3Client.listObjectsV2(_ as ListObjectsV2Request) >> EMPTY_LISTING

        when:
        provider.move(source, target)

        then:
        1 * s3Client.copyObject(_ as CopyObjectRequest) >> CopyObjectResponse.builder().build()
        1 * s3Client.deleteObject({ DeleteObjectRequest r -> r.key() == 'prefix/a.txt' }) >> DeleteObjectResponse.builder().build()
    }

    // ==================== Multipart uploads ====================

    def "large files are uploaded in parts"() {
        given:
        def p = writablePath('prefix/big.bin')
        nothingExists()
        provider.uploader.multipartThreshold = 10
        provider.uploader.minPartSize = 4
        def parts = [:]
        s3Client.createMultipartUpload(_ as CreateMultipartUploadRequest) >> CreateMultipartUploadResponse.builder().uploadId('upload-1').build()
        s3Client.uploadPart(_ as UploadPartRequest, _ as RequestBody) >> { UploadPartRequest r, RequestBody b ->
            assert r.uploadId() == 'upload-1'
            parts[r.partNumber()] = new String(bytesOf(b))
            UploadPartResponse.builder().eTag("etag-${r.partNumber()}".toString()).build()
        }

        when:
        OutputStream out = provider.newOutputStream(p)
        out.write('0123456789A'.bytes)
        out.close()

        then:
        1 * s3Client.completeMultipartUpload({ CompleteMultipartUploadRequest r ->
            r.multipartUpload().parts()*.partNumber() == [1, 2, 3] &&
            r.multipartUpload().parts()*.eTag() == ['etag-1', 'etag-2', 'etag-3']
        }) >> CompleteMultipartUploadResponse.builder().build()
        parts == [1: '0123', 2: '4567', 3: '89A']
        0 * s3Client.putObject(*_)
    }

    def "a failed multipart upload is aborted"() {
        given:
        def p = writablePath('prefix/big.bin')
        nothingExists()
        provider.uploader.multipartThreshold = 10
        provider.uploader.minPartSize = 4
        s3Client.createMultipartUpload(_ as CreateMultipartUploadRequest) >> CreateMultipartUploadResponse.builder().uploadId('upload-1').build()
        s3Client.uploadPart(_ as UploadPartRequest, _ as RequestBody) >> { throw S3Exception.builder().message('boom').statusCode(500).build() }

        when:
        OutputStream out = provider.newOutputStream(p)
        out.write('0123456789A'.bytes)
        out.close()

        then:
        thrown(IOException)
        1 * s3Client.abortMultipartUpload({ AbortMultipartUploadRequest r -> r.uploadId() == 'upload-1' })
        0 * s3Client.completeMultipartUpload(*_)
    }

    def "the part size grows to stay under the part limit"() {
        given:
        def uploader = new LaminS3Uploader(minPartSize: 8L * 1024 * 1024, maxParts: 10_000)

        expect:
        uploader.partSizeFor(100L * 1024 * 1024) == 8L * 1024 * 1024
        uploader.partSizeFor(5L * 1024 * 1024 * 1024 * 1024) == 525L * 1024 * 1024
    }

    // ==================== Provider mismatch ====================

    def "newInputStream() with non-LaminS3Path throws ProviderMismatchException"() {
        given:
        def localPath = java.nio.file.Paths.get('/tmp/local.txt')

        when:
        provider.newInputStream(localPath)

        then:
        thrown(ProviderMismatchException)
    }
}
