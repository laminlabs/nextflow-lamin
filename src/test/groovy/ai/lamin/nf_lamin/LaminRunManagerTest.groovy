package ai.lamin.nf_lamin

import ai.lamin.nf_lamin.instance.Instance
import ai.lamin.nf_lamin.model.ArtifactAnnotation
import ai.lamin.nf_lamin.model.RunStatus
import ai.lamin.nf_lamin.nio.LaminS3FileSystem
import ai.lamin.nf_lamin.nio.LaminS3FileSystemProvider
import ai.lamin.nf_lamin.nio.LaminS3Path
import ai.lamin.nf_lamin.nio.LaminStorageTarget
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import nextflow.Session
import nextflow.exception.AbortSignalException
import nextflow.script.WorkflowMetadata
import spock.lang.Specification

import java.lang.reflect.Field
import java.net.URI
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sun.misc.Signal

class LaminRunManagerTest extends Specification {

    def setup() {
        LaminRunManager.instance.reset()
    }

    private static void injectField(Object target, String fieldName, Object value) {
        Field field = target.class.getDeclaredField(fieldName)
        field.accessible = true
        field.set(target, value)
    }

    def 'stores and exposes transform and run metadata'() {
        given:
        Map<String, Object> transform = [uid: 'T123', id: 42]
        Map<String, Object> run = [uid: 'R456', id: 99]

        when:
        LaminRunManager.instance.updateTransform(transform)
        LaminRunManager.instance.updateRun(run)

        then:
        LaminRunManager.instance.transform.uid == 'T123'
        LaminRunManager.instance.run.uid == 'R456'

        and:
        def extension = new LaminExtension()
        extension.getTransformUid() == 'T123'
        extension.getRunUid() == 'R456'
    }

    def 'reset clears stored state'() {
        given:
        LaminRunManager.instance.updateRun([uid: 'R999'])

        when:
        LaminRunManager.instance.reset()

        then:
        LaminRunManager.instance.run == null
        new LaminExtension().getRunUid() == null
    }

    def 'fetchOrCreateArtifact passes branch_id and space_id when resolved'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        def config = new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key'])
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', config)
        injectField(manager, 'resolvedBranchId', 7)
        injectField(manager, 'resolvedSpaceId', 3)

        def mockPath = Mock(Path)
        mockPath.toUri() >> new URI('s3://test-bucket/test-file.txt')

        mockInstance.getArtifactByPath('s3://test-bucket/test-file.txt') >> null
        mockInstance.getOwner() >> 'testorg'
        mockInstance.getName() >> 'testinst'

        when:
        Map<String, Object> result = manager.fetchOrCreateArtifact([path: mockPath])

        then:
        1 * mockInstance.createArtifact({ Map args ->
            args.get('branch_id') == 7 &&
            args.get('space_id') == 3 &&
            args.get('path') == 's3://test-bucket/test-file.txt'
        }) >> [uid: 'testuid1234567890ab', branch: 7.0]
        result != null
    }

    private LaminS3Path publishedPath(String key, LaminStorageTarget target) {
        def fs = new LaminS3FileSystem(Mock(LaminS3FileSystemProvider), 's3://bucket/JwMEKs04D9WJ', Mock(AwsS3Client), 'write', target)
        return new LaminS3Path(fs, key)
    }

    def 'fetchOrCreateArtifact takes the space from the storage location a published file resolved to'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key']))
        injectField(manager, 'resolvedSpaceId', 3)
        def target = new LaminStorageTarget(storageRoot: 's3://bucket/JwMEKs04D9WJ', storageUid: 'JwMEKs04D9WJ', spaceId: 5)
        def path = publishedPath('JwMEKs04D9WJ/results/a.txt', target)
        mockInstance.getArtifactByPath(_) >> null

        when:
        manager.fetchOrCreateArtifact([path: path])

        then:
        1 * mockInstance.createArtifact({ Map args ->
            args.get('space_id') == 5 && args.get('path') == 's3://bucket/JwMEKs04D9WJ/results/a.txt'
        }) >> [uid: 'testuid1234567890ab']
    }

    def 'fetchOrCreateArtifact keeps the configured space when the storage location has none'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key']))
        injectField(manager, 'resolvedSpaceId', 3)
        def target = new LaminStorageTarget(storageRoot: 's3://bucket/JwMEKs04D9WJ', storageUid: 'JwMEKs04D9WJ')
        def path = publishedPath('JwMEKs04D9WJ/results/a.txt', target)
        mockInstance.getArtifactByPath(_) >> null

        when:
        manager.fetchOrCreateArtifact([path: path])

        then:
        1 * mockInstance.createArtifact({ Map args -> args.get('space_id') == 3 }) >> [uid: 'testuid1234567890ab']
    }

    def 'an index file announced by onWorkflowOutput is registered when it is published'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def index = remotePath('s3://bucket/results/reports/index.csv')
        mockInstance.getArtifactByPath(_) >> null

        when: 'the output block is announced, before the index file exists'
        manager.rememberOutputName(index, 'reports')

        then:
        0 * mockInstance.createArtifact(_)

        when: 'the index file is published'
        manager.createOutputArtifactOnFilePublish(null, index, null)

        then:
        1 * mockInstance.createArtifact({ Map args -> (args.description as String).contains("'reports'") }) >> [uid: 'A1', id: 11, run: 1]
    }

    def 'fetchOrCreateArtifact omits branch_id and space_id when not resolved'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        def config = new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key'])
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', config)
        // resolvedBranchId and resolvedSpaceId remain null from reset() in setup()

        def mockPath = Mock(Path)
        mockPath.toUri() >> new URI('s3://test-bucket/test-file.txt')

        mockInstance.getArtifactByPath('s3://test-bucket/test-file.txt') >> null
        mockInstance.getOwner() >> 'testorg'
        mockInstance.getName() >> 'testinst'

        when:
        Map<String, Object> result = manager.fetchOrCreateArtifact([path: mockPath])

        then:
        1 * mockInstance.createArtifact({ Map args ->
            !args.containsKey('branch_id') &&
            !args.containsKey('space_id')
        }) >> [uid: 'testuid1234567890ab']
        result != null
    }

    def 'startRun omits the reference when not running on Seqera Platform'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key']))
        injectField(manager, 'run', [uid: 'R456', id: 99] as Map<String, Object>)

        // Stub(WorkflowMetadata) has no getPlatform(), like Nextflow 25.10
        def metadata = Stub(WorkflowMetadata) {
            getStart() >> OffsetDateTime.now()
        }
        injectField(manager, 'session', Stub(Session) { getWorkflowMetadata() >> metadata })

        when:
        manager.startRun()

        then:
        1 * mockInstance.updateRecord({ Map args ->
            Map data = args.data as Map
            !data.containsKey('reference') &&
            !data.containsKey('reference_type')
        }) >> [uid: 'R456']
    }

    def 'startRun stores the watch url and reference type when running on Seqera Platform'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key']))
        injectField(manager, 'run', [uid: 'R456', id: 99] as Map<String, Object>)

        String watchUrl = 'https://cloud.seqera.io/orgs/o/workspaces/w/watch/b0siCig3qoUvZ'
        def metadata = new MetadataWithPlatform(platform: new PlatformWithUrl(workflowUrl: watchUrl))
        injectField(manager, 'session', Stub(Session) { getWorkflowMetadata() >> metadata })

        when:
        manager.startRun()

        then:
        1 * mockInstance.updateRecord({ Map args ->
            Map data = args.data as Map
            data.get('reference') == watchUrl &&
            data.get('reference_type') == 'Seqera'
        }) >> [uid: 'R456']
    }

    def 'startRun still marks the run as started when the reference cannot be read'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'testorg/testinst', api_key: 'test-key']))
        injectField(manager, 'run', [uid: 'R456', id: 99] as Map<String, Object>)

        // fail the second metadata read -- the reference lookup -- with a linkage error
        int calls = 0
        def session = Stub(Session) {
            getWorkflowMetadata() >> {
                if (calls++ > 0) {
                    throw new NoClassDefFoundError('nextflow/script/PlatformMetadata')
                }
                return Stub(WorkflowMetadata) { getStart() >> OffsetDateTime.now() }
            }
        }
        injectField(manager, 'session', session)

        when:
        manager.startRun()

        then:
        noExceptionThrown()
        1 * mockInstance.updateRecord({ Map args ->
            Map data = args.data as Map
            data.containsKey('_status_code') &&
            !data.containsKey('reference')
        }) >> [uid: 'R456']
    }

    /** Mimics Nextflow >= 26.04, where WorkflowMetadata exposes getPlatform(). */
    static class MetadataWithPlatform extends WorkflowMetadata {

        Object platform

    }

    /** Mimics nextflow.script.PlatformMetadata. */
    static class PlatformWithUrl {

        Object workflowUrl

    }

    def 'createInputArtifactsAsync returns before worker finishes (non-blocking)'() {
        given:
        def manager = LaminRunManager.instance
        def mockInstance = Mock(Instance)
        def config = new LaminConfig([instance: 'org/inst', api_key: 'key'])
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', config)
        injectField(manager, 'run', [uid: 'R1', id: 1])

        def workerStarted = new CountDownLatch(1)
        def releaseWorker = new CountDownLatch(1)
        mockInstance.createArtifact(_) >> {
            workerStarted.countDown()
            releaseWorker.await(5, TimeUnit.SECONDS)
            [uid: 'A1', run: 1]
        }

        def mockPath = Mock(Path)
        mockPath.toUri() >> new URI('s3://bucket/file.txt')

        when:
        long before = System.nanoTime()
        manager.createInputArtifactsAsync('test-task', [mockPath])
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L

        then: 'method returned quickly without waiting for the API call'
        elapsedMs < 3000

        and: 'worker actually started in background'
        workerStarted.await(5, TimeUnit.SECONDS)

        cleanup:
        releaseWorker.countDown()
    }

    def 'determineRunStatus maps session state to run status'() {
        given:
        def manager = LaminRunManager.instance
        def session = Stub(Session) {
            isSuccess() >> success
            isCancelled() >> cancelled
            getError() >> error
        }
        injectField(manager, 'session', session)

        expect:
        manager.determineRunStatus() == expected

        where:
        scenario                       | success | cancelled | error                                          || expected
        'successful run'               | true    | false     | null                                           || RunStatus.COMPLETED
        'graceful cancel flag'         | false   | true      | null                                           || RunStatus.ABORTED
        'SIGTERM from Seqera cancel'   | false   | false     | new AbortSignalException(new Signal('TERM'))   || RunStatus.ABORTED
        'SIGINT from local Ctrl+C'     | false   | false     | new AbortSignalException(new Signal('INT'))    || RunStatus.ABORTED
        'task failure'                 | false   | false     | new RuntimeException('task failed')            || RunStatus.ERRORED
        'error without cause'          | false   | false     | null                                           || RunStatus.ERRORED
    }

    // ========== annotateArtifact ==========

    /**
     * A remote path, since local paths are never tracked as artifacts.
     */
    private Path remotePath(String uri) {
        def path = Stub(Path)
        path.toUri() >> new URI(uri)
        path.toAbsolutePath() >> path
        path.normalize() >> path
        return path
    }

    private static LaminRunManager annotatingManager(Instance mockInstance) {
        def manager = LaminRunManager.instance
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'org/inst', api_key: 'key']))
        injectField(manager, 'run', [uid: 'R1', id: 1])
        return manager
    }

    private static Set<String> readKeys(String fieldName) {
        def field = LaminRunManager.getDeclaredField(fieldName)
        field.accessible = true
        def value = field.get(LaminRunManager.instance)
        return (value instanceof Map ? (value as Map).keySet() : value) as Set<String>
    }

    def 'applies an annotation registered before the file is published'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def source = remotePath('s3://bucket/work/output.json')
        def target = remotePath('s3://bucket/results/output.json')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A1', id: 11, run: 1]
        mockInstance.getRecord(_) >> [uid: 'ulab1234', id: 5]

        when: 'the workflow annotates the file it holds, before it is published'
        manager.registerAnnotation(source, ArtifactAnnotation.fromMap([
            kind: 'dataset',
            description: 'Summary',
            ulabel_uids: ['ulab1234']
        ]))
        manager.createOutputArtifactOnFilePublish(source, target, null)

        then:
        1 * mockInstance.updateRecord({ Map args ->
            args.uid == 'A1' && args.data.kind == 'dataset' && args.data.description == 'Summary'
        })
        1 * mockInstance.upsertRecord({ Map args ->
            args.modelName == 'artifactulabel' && args.data.artifact_id == 11 && args.data.ulabel_id == 5
        })
    }

    def 'applies an annotation registered after the file was published'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def source = remotePath('s3://bucket/work/output.json')
        def target = remotePath('s3://bucket/results/output.json')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A1', id: 11, run: 1]

        when: 'publishing wins the race with the map operator'
        manager.createOutputArtifactOnFilePublish(source, target, null)
        manager.registerAnnotation(source, ArtifactAnnotation.fromMap([kind: 'dataset']))
        manager.awaitArtifactTasks()

        then:
        1 * mockInstance.updateRecord({ Map args -> args.uid == 'A1' && args.data.kind == 'dataset' })
    }

    def 'annotates an artifact under the path it was published to'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def source = remotePath('s3://bucket/work/output.json')
        def target = remotePath('s3://bucket/results/output.json')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A1', id: 11, run: 1]

        when:
        manager.registerAnnotation(target, ArtifactAnnotation.fromMap([kind: 'dataset']))
        manager.createOutputArtifactOnFilePublish(source, target, null)

        then:
        1 * mockInstance.updateRecord({ Map args -> args.uid == 'A1' })
    }

    def 'applies every annotation registered for the same file'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def source = remotePath('s3://bucket/work/output.json')
        def target = remotePath('s3://bucket/results/output.json')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A1', id: 11, run: 1]
        mockInstance.getRecord(_) >> [uid: 'ulab1234', id: 5]

        when:
        manager.registerAnnotation(source, ArtifactAnnotation.fromMap([kind: 'dataset']))
        manager.registerAnnotation(source, ArtifactAnnotation.fromMap([ulabel_uids: ['ulab1234']]))
        manager.createOutputArtifactOnFilePublish(source, target, null)

        then:
        1 * mockInstance.updateRecord({ Map args -> args.data.kind == 'dataset' })
        1 * mockInstance.upsertRecord({ Map args -> args.modelName == 'artifactulabel' })
    }

    def 'annotates an input artifact'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def input = remotePath('s3://bucket/inputs/reads.fastq')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A2', id: 22, run: 1]

        when:
        manager.registerAnnotation(input, ArtifactAnnotation.fromMap([description: 'Raw reads']))
        manager.createInputArtifact(input)

        then:
        1 * mockInstance.updateRecord({ Map args -> args.uid == 'A2' && args.data.description == 'Raw reads' })
    }

    def 'ignores an empty annotation'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)

        when:
        manager.registerAnnotation(remotePath('s3://bucket/work/output.json'), ArtifactAnnotation.fromMap([:]))

        then:
        readKeys('pendingAnnotations').isEmpty()
    }

    def 'ignores an annotation when no instance is configured'() {
        given:
        def manager = LaminRunManager.instance

        when:
        manager.registerAnnotation(remotePath('s3://bucket/work/output.json'),
            ArtifactAnnotation.fromMap([kind: 'dataset']))

        then:
        readKeys('pendingAnnotations').isEmpty()
    }

    def 'does not apply annotations in dry-run mode'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = LaminRunManager.instance
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'org/inst', api_key: 'key', dry_run: true]))

        when:
        manager.registerAnnotation(remotePath('s3://bucket/work/output.json'),
            ArtifactAnnotation.fromMap([kind: 'dataset']))

        then:
        readKeys('pendingAnnotations').isEmpty()
        0 * mockInstance.updateRecord(_)
    }

    def 'reports annotations that never matched an artifact'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = annotatingManager(mockInstance)
        def published = remotePath('s3://bucket/work/published.json')
        def target = remotePath('s3://bucket/results/published.json')
        def neverPublished = remotePath('s3://bucket/work/dropped.json')

        mockInstance.getArtifactByPath(_) >> null
        mockInstance.createArtifact(_) >> [uid: 'A1', id: 11, run: 1]

        when:
        manager.registerAnnotation(published, ArtifactAnnotation.fromMap([kind: 'dataset']))
        manager.registerAnnotation(neverPublished, ArtifactAnnotation.fromMap([kind: 'dataset']))
        manager.createOutputArtifactOnFilePublish(published, target, null)
        manager.warnUnmatchedAnnotations()

        then:
        readKeys('matchedAnnotationKeys') == ['s3://bucket/work/published.json'] as Set
        readKeys('pendingAnnotations').contains('s3://bucket/work/dropped.json')
    }
}
