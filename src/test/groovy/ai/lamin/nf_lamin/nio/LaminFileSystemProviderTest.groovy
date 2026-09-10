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
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.ProviderMismatchException

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client

import ai.lamin.nf_lamin.LaminConfig
import ai.lamin.nf_lamin.hub.CloudAccessResponse
import ai.lamin.nf_lamin.hub.InstanceSettings
import ai.lamin.nf_lamin.hub.LaminHub
import ai.lamin.nf_lamin.instance.Instance

/**
 * Tests for LaminFileSystemProvider
 */
class LaminFileSystemProviderTest extends Specification {

    /**
     * Test subclass with the connection, credentials and lamin-s3 provider injected.
     */
    static class TestableLaminFileSystemProvider extends LaminFileSystemProvider {
        Instance instance
        LaminHub hub
        LaminConfig config
        CloudAccessResponse cloudAccess
        LaminS3FileSystemProvider s3Provider
        List<String> storagePathsRequested = []

        @Override
        Instance getInstance(String owner, String name) { instance }

        @Override
        protected LaminHub getHub() { hub }

        @Override
        protected LaminConfig getConfig() { config }

        @Override
        protected CloudAccessResponse getCachedCloudAccess(LaminHub hub, String storageRoot) { cloudAccess }

        @Override
        protected LaminS3FileSystemProvider getS3Provider() { s3Provider }

        @Override
        protected Path asStoragePath(String uri) {
            storagePathsRequested << uri
            return Paths.get('/resolved', uri.replaceFirst('^s3://', ''))
        }
    }

    static class TestableLaminS3FileSystemProvider extends LaminS3FileSystemProvider {
        AwsS3Client injectedClient

        @Override
        protected AwsS3Client createS3Client(AwsCredentialsProvider credentials, String region) {
            return injectedClient
        }
    }

    static final String STORAGE_ROOT = 's3://lamin-eu/JwMEKs04D9WJ'

    LaminFileSystemProvider provider

    def setup() {
        provider = new LaminFileSystemProvider()
    }

    InstanceSettings settings() {
        new InstanceSettings([
            id: '037ba1e0-8d80-4f91-a902-75a47735076a',
            owner: 'laminlabs',
            name: 'lamindata',
            schema_id: '90541d56-0ee5-4757-b93a-8afa8ace1bd1',
            api_url: 'https://api.example.org',
            lnid: 'InstUid00001',
            storage: [lnid: 'DefaultSt001', root: 's3://lamindata', type: 's3', region: 'us-east-1'],
        ])
    }

    CloudAccessResponse cloudAccess(String role) {
        new CloudAccessResponse([
            Credentials: [AccessKeyId: 'ASIAEXAMPLE', SecretAccessKey: 'secret', SessionToken: 'token'],
            StorageAccessibility: [storageRoot: STORAGE_ROOT, role: role, isPublic: false, isManaged: true],
        ])
    }

    TestableLaminFileSystemProvider publishProvider(String role = 'write', Map configOpts = [instance: 'laminlabs/lamindata', api_key: 'key']) {
        def instance = Mock(Instance) {
            getSettings() >> settings()
            getRecord(_) >> [id: 7, uid: 'JwMEKs04D9WJ', root: STORAGE_ROOT, type: 's3', region: 'eu-central-1',
                             instance_uid: 'InstUid00001', space_id: 5]
        }
        new TestableLaminFileSystemProvider(
            instance: instance,
            hub: Mock(LaminHub),
            config: new LaminConfig(configOpts, false),
            cloudAccess: role ? cloudAccess(role) : new CloudAccessResponse([:]),
            s3Provider: new TestableLaminS3FileSystemProvider(injectedClient: Mock(AwsS3Client)),
        )
    }

    // ==================== Publish target resolution ====================

    def "getPath resolves a storage URI to a writable lamin-s3 path"() {
        given:
        def provider = publishProvider('write')

        when:
        def path = provider.getPath(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&prefix=results'))

        then:
        path instanceof LaminS3Path
        ((LaminS3Path) path).key == 'JwMEKs04D9WJ/results'
        with((LaminS3FileSystem) path.fileSystem) {
            storageRoot == STORAGE_ROOT
            !isReadOnly()
            target.storageUid == 'JwMEKs04D9WJ'
            target.spaceId == 5
        }
    }

    def "getPath accepts the admin role for publishing"() {
        given:
        def provider = publishProvider('admin')

        when:
        def path = provider.getPath(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        path instanceof LaminS3Path
        !path.fileSystem.isReadOnly()
    }

    def "getPath refuses a publish target when the hub grants only read access"() {
        given:
        def provider = publishProvider('read')

        when:
        provider.getPath(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("grants only 'read' access")
        e.message.contains(STORAGE_ROOT)
    }

    def "getPath refuses a publish target without an api key"() {
        given:
        def provider = publishProvider('write', [instance: 'laminlabs/lamindata', api_key: null])

        when:
        provider.getPath(new URI('lamin://laminlabs/lamindata?prefix=results'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('lamin.api_key')
    }

    def "getPath falls back to the standard provider when the hub has no credentials for the storage"() {
        given:
        def provider = publishProvider(null)

        when:
        def path = provider.getPath(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&prefix=results'))

        then:
        provider.storagePathsRequested == ["${STORAGE_ROOT}/results".toString()]
        path == Paths.get('/resolved/lamin-eu/JwMEKs04D9WJ/results')
    }

    def "getPath falls back to the standard provider when credential management is off"() {
        given:
        def provider = publishProvider('write', [instance: 'laminlabs/lamindata', api_key: 'key', features: [manage_s3_credentials: false]])

        when:
        provider.getPath(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        provider.storagePathsRequested == [STORAGE_ROOT]
    }

    def "getPath uses the default storage for a bare instance URI"() {
        given:
        def provider = publishProvider('write')
        provider.cloudAccess = new CloudAccessResponse([
            Credentials: [AccessKeyId: 'ASIAEXAMPLE', SecretAccessKey: 'secret', SessionToken: 'token'],
            StorageAccessibility: [storageRoot: 's3://lamindata', role: 'write', isManaged: true],
        ])

        when:
        def path = provider.getPath(new URI('lamin://laminlabs/lamindata'))

        then:
        path instanceof LaminS3Path
        ((LaminS3Path) path).key == ''
        ((LaminS3FileSystem) path.fileSystem).storageRoot == 's3://lamindata'
    }

    // ==================== getScheme Tests ====================

    def "getScheme should return lamin"() {
        expect:
        provider.scheme == 'lamin'
    }

    // ==================== toLaminPath Tests ====================

    def "toLaminPath should return LaminPath for valid path"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def laminPath = provider.getPath(uri)

        when:
        def result = LaminFileSystemProvider.toLaminPath(laminPath)

        then:
        result.is(laminPath)
    }

    def "toLaminPath should throw ProviderMismatchException for non-LaminPath"() {
        given:
        def localPath = Paths.get('/local/path')

        when:
        LaminFileSystemProvider.toLaminPath(localPath)

        then:
        thrown(ProviderMismatchException)
    }

    def "toLaminPath should throw ProviderMismatchException for null"() {
        when:
        LaminFileSystemProvider.toLaminPath(null)

        then:
        thrown(ProviderMismatchException)
    }

    // ==================== newFileSystem Tests ====================

    def "newFileSystem should create new file system"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')

        when:
        def fs = provider.newFileSystem(uri, [:])

        then:
        fs instanceof LaminFileSystem
        fs.instanceSlug == 'laminlabs/lamindata'
    }

    def "newFileSystem should return existing file system for same instance"() {
        given:
        def uri1 = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = new URI('lamin://laminlabs/lamindata/artifact/uid456')

        when:
        def fs1 = provider.newFileSystem(uri1, [:])
        def fs2 = provider.newFileSystem(uri2, [:])

        then:
        fs1.is(fs2)
    }

    def "newFileSystem should create different file systems for different instances"() {
        given:
        def uri1 = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = new URI('lamin://other/instance/artifact/uid456')

        when:
        def fs1 = provider.newFileSystem(uri1, [:])
        def fs2 = provider.newFileSystem(uri2, [:])

        then:
        !fs1.is(fs2)
        fs1.instanceSlug == 'laminlabs/lamindata'
        fs2.instanceSlug == 'other/instance'
    }

    // ==================== getFileSystem Tests ====================

    def "getFileSystem should return existing file system"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def created = provider.newFileSystem(uri, [:])

        when:
        def retrieved = provider.getFileSystem(uri)

        then:
        retrieved.is(created)
    }

    def "getFileSystem should throw FileSystemNotFoundException for non-existent"() {
        given:
        def uri = new URI('lamin://nonexistent/instance/artifact/uid123')

        when:
        provider.getFileSystem(uri)

        then:
        thrown(FileSystemNotFoundException)
    }

    // ==================== getOrCreateFileSystem Tests ====================

    def "getOrCreateFileSystem should create new file system"() {
        given:
        def uri = new URI('lamin://newowner/newinstance/artifact/uid123')

        when:
        def fs = provider.getOrCreateFileSystem(uri)

        then:
        fs instanceof LaminFileSystem
        fs.instanceSlug == 'newowner/newinstance'
    }

    def "getOrCreateFileSystem should return existing file system"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def created = provider.newFileSystem(uri, [:])

        when:
        def retrieved = provider.getOrCreateFileSystem(uri)

        then:
        retrieved.is(created)
    }

    // ==================== getPath Tests ====================

    def "getPath should create LaminPath"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')

        when:
        def path = provider.getPath(uri)

        then:
        path instanceof LaminPath
        path.toUriString() == 'lamin://laminlabs/lamindata/artifact/uid123'
    }

    def "getPath should create LaminPath with sub-path"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123/subdir/file.txt')

        when:
        def path = provider.getPath(uri)

        then:
        path instanceof LaminPath
        ((LaminPath) path).subPath == 'subdir/file.txt'
    }

    // ==================== removeFileSystem Tests ====================

    def "removeFileSystem should remove from cache"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        provider.newFileSystem(uri, [:])

        when:
        provider.removeFileSystem('laminlabs/lamindata')
        provider.getFileSystem(uri)

        then:
        thrown(FileSystemNotFoundException)
    }

    // ==================== Unsupported Write Operations Tests ====================

    def "newOutputStream should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        when:
        provider.newOutputStream(path)

        then:
        thrown(UnsupportedOperationException)
    }

    def "createDirectory should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        when:
        provider.createDirectory(path)

        then:
        thrown(UnsupportedOperationException)
    }

    def "delete should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        when:
        provider.delete(path)

        then:
        thrown(UnsupportedOperationException)
    }

    def "move should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def source = provider.getPath(uri)
        def target = Paths.get('/local/target')

        when:
        provider.move(source, target)

        then:
        thrown(UnsupportedOperationException)
    }

    def "copy to lamin path should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def target = provider.getPath(uri)
        def source = Paths.get('/local/source')

        when:
        provider.copy(source, target)

        then:
        thrown(UnsupportedOperationException)
    }

    def "getFileStore should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        when:
        provider.getFileStore(path)

        then:
        thrown(UnsupportedOperationException)
    }

    def "setAttribute should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        when:
        provider.setAttribute(path, 'attr', 'value')

        then:
        thrown(UnsupportedOperationException)
    }

    def "upload should throw UnsupportedOperationException"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def target = provider.getPath(uri)
        def source = Paths.get('/local/source')

        when:
        provider.upload(source, target)

        then:
        thrown(UnsupportedOperationException)
    }

    // ==================== isSameFile Tests ====================

    def "isSameFile should return true for equal LaminPaths"() {
        given:
        def uri1 = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path1 = provider.getPath(uri1)
        def path2 = provider.getPath(uri2)

        expect:
        provider.isSameFile(path1, path2)
    }

    def "isSameFile should return false for different LaminPaths"() {
        given:
        def uri1 = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = new URI('lamin://laminlabs/lamindata/artifact/uid456')
        def path1 = provider.getPath(uri1)
        def path2 = provider.getPath(uri2)

        expect:
        !provider.isSameFile(path1, path2)
    }

    def "isSameFile should return false when mixing with non-LaminPath"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def laminPath = provider.getPath(uri)
        def localPath = Paths.get('/local/path')

        expect:
        !provider.isSameFile(laminPath, localPath)
        !provider.isSameFile(localPath, laminPath)
    }

    // ==================== isHidden Tests ====================

    def "isHidden should return false"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def path = provider.getPath(uri)

        expect:
        !provider.isHidden(path)
    }

    // ==================== FileSystemTransferAware Tests ====================

    def "canUpload should return false"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def target = provider.getPath(uri)
        def source = Paths.get('/local/source')

        expect:
        !provider.canUpload(source, target)
    }

    def "canDownload should return true for LaminPath to local path"() {
        given:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid123')
        def source = provider.getPath(uri)
        def target = Paths.get('/local/target')

        expect:
        provider.canDownload(source, target)
    }

    def "canDownload should return false for non-LaminPath source"() {
        given:
        def source = Paths.get('/local/source')
        def target = Paths.get('/local/target')

        expect:
        !provider.canDownload(source, target)
    }
}
