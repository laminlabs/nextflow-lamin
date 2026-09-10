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

package ai.lamin.nf_lamin

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Const
import nextflow.Session
import nextflow.exception.AbortSignalException
import nextflow.file.FileHelper
import nextflow.script.WorkflowMetadata

import ai.lamin.lamin_api_client.ApiException
import ai.lamin.lamin_api_client.model.Account
import ai.lamin.nf_lamin.hub.LaminHub
import ai.lamin.nf_lamin.hub.LaminHubSettings
import ai.lamin.nf_lamin.instance.Instance
import ai.lamin.nf_lamin.instance.PermissionDeniedException
import ai.lamin.nf_lamin.hub.InstanceSettings
import ai.lamin.nf_lamin.model.ArtifactAnnotation
import ai.lamin.nf_lamin.model.RunStatus
import ai.lamin.nf_lamin.nio.LaminPath
import ai.lamin.nf_lamin.nio.LaminS3FileSystem
import ai.lamin.nf_lamin.nio.LaminS3Path
import ai.lamin.nf_lamin.nio.LaminStorageTarget
import ai.lamin.nf_lamin.util.PathUtils
import ai.lamin.nf_lamin.util.SeqeraPlatformHelper
import ai.lamin.nf_lamin.util.TransformInfoHelper
import ai.lamin.nf_lamin.config.ArtifactConfig
import ai.lamin.nf_lamin.config.ArtifactEvaluation
import ai.lamin.nf_lamin.config.ConfigUtils
import ai.lamin.nf_lamin.config.ApiConfig

/**
 * Holds shared state about the currently active Lamin transform and run.
 *
 * The observer updates this singleton as the workflow lifecycle progresses,
 * while extension functions expose the captured metadata to Nextflow scripts.
 */
@Slf4j
@CompileStatic
final class LaminRunManager {

    private static final LaminRunManager INSTANCE = new LaminRunManager()

    private static final AtomicInteger artifactThreadCount = new AtomicInteger(0)

    private final ConcurrentHashMap<String, ReentrantLock> artifactLocks = new ConcurrentHashMap<>()
    private volatile ExecutorService artifactExecutor

    private volatile Session session
    private volatile LaminConfig config
    private volatile LaminHubSettings resolvedConfig
    private volatile LaminHub hub
    private volatile Instance laminInstance
    private volatile Map<String, Object> transform
    private volatile Map<String, Object> run

    // Handle of the connected account, captured in testConnection()
    private volatile String accountHandle

    // Cached resolved space and branch IDs (resolved once at initialization)
    private volatile Integer resolvedSpaceId
    private volatile Integer resolvedBranchId

    // Cache for resolved record lookups (key: "module/model/uidOrName", value: record map)
    private final Map<String, Map> recordResolutionCache = Collections.synchronizedMap(new LinkedHashMap<String, Map>())

    // Cache of published output artifacts (key: path URI string, value: artifact map)
    // Written by createOutputArtifact; read by createOutputArtifact(labels) and trackWorkflowOutput
    private final Map<String, Map> publishedArtifactsByPath = Collections.synchronizedMap(new LinkedHashMap<String, Map>())

    // Output names of index files announced by onWorkflowOutput before they were written
    private final Map<String, String> pendingOutputNames = new ConcurrentHashMap<String, String>()

    // Annotations requested from the workflow via annotateArtifact(), keyed by annotation key
    private final Map<String, List<ArtifactAnnotation>> pendingAnnotations = new ConcurrentHashMap<String, List<ArtifactAnnotation>>()

    // Artifacts already created, keyed by every annotation key that resolves to them
    private final Map<String, List<Map<String, Object>>> artifactsByAnnotationKey = new ConcurrentHashMap<String, List<Map<String, Object>>>()

    // Annotation keys that matched at least one artifact
    private final Set<String> matchedAnnotationKeys = ConcurrentHashMap.newKeySet()

    // Tracks whether the one-time local file warning has been shown this session
    private volatile boolean localFileWarningShown = false

    private LaminRunManager() {
    }

    static LaminRunManager getInstance() {
        return INSTANCE
    }

    synchronized void reset() {
        session = null
        config = null
        resolvedConfig = null
        hub = null
        laminInstance = null
        transform = null
        run = null
        accountHandle = null
        resolvedSpaceId = null
        resolvedBranchId = null
        LaminConnection.getInstance().reset()
        recordResolutionCache.clear()
        publishedArtifactsByPath.clear()
        pendingOutputNames.clear()
        pendingAnnotations.clear()
        artifactsByAnnotationKey.clear()
        matchedAnnotationKeys.clear()
        artifactExecutor = createArtifactExecutor(ApiConfig.DEFAULT_MAX_WORKERS)
    }

    /**
     * Create the worker thread pool used to create artifacts in parallel.
     *
     * Shuts down any previously created executor before replacing it.
     *
     * @param maxWorkers The maximum number of worker threads
     * @return the new executor service
     */
    private ExecutorService createArtifactExecutor(int maxWorkers) {
        if (artifactExecutor != null && !artifactExecutor.isShutdown()) {
            artifactExecutor.shutdownNow()
        }
        return Executors.newFixedThreadPool(maxWorkers) { Runnable r ->
            Thread t = new Thread(r, 'lamin-worker-' + artifactThreadCount.incrementAndGet())
            t.setDaemon(true)
            return t
        }
    }

    /**
     * Get or create a cached Instance object for the specified LaminDB instance.
     *
     * @param instanceOwner The owner (user or organization) of the instance
     * @param instanceName The name of the instance
     * @return An Instance object, either from cache or newly created
     */
    Instance getInstance(String instanceOwner, String instanceName) {
        return LaminConnection.getInstance().getInstance(instanceOwner, instanceName)
    }

    /**
     * Get the LaminHub client.
     * @return the hub
     */
    LaminHub getHub() {
        return hub
    }

    LaminConfig getConfig() {
        return config
    }

    synchronized void updateTransform(Map<String, Object> data) {
        transform = data != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data)) : null
    }

    Map<String, Object> getTransform() {
        return transform
    }

    synchronized void updateRun(Map<String, Object> data) {
        run = data != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data)) : null
    }

    Map<String, Object> getRun() {
        return run
    }

    Instance getCurrentInstance() {
        return laminInstance
    }

    synchronized void setCurrentInstance(Instance instance) {
        laminInstance = instance
    }

    /**
     * Returns the instance slug in the format "owner/name".
     *
     * @return the instance slug (e.g., "laminlabs/lamindata") or {@code null} if not available
     */
    String getInstanceSlug() {
        Instance inst = laminInstance
        if (inst == null) {
            return null
        }
        InstanceSettings settings = inst.getSettings()
        if (settings == null) {
            return null
        }
        return "${settings.owner}/${settings.name}"
    }

    void initializeRunManager(Session session) {
        log.debug 'LaminRunManager.initializeRunManager'
        reset()
        this.session = session

        log.debug 'Parsing Lamin configuration from session'
        this.config = LaminConfig.parseConfig(session)
        log.debug "Parsed config: ${config.toString()}"

        // Resize the worker pool now that the configured value is known
        int maxWorkers = config.apiConfig.maxWorkers
        log.debug "Configuring artifact worker pool with ${maxWorkers} thread(s)"
        this.artifactExecutor = createArtifactExecutor(maxWorkers)

        log.debug 'Resolving Lamin configuration with hub settings'
        this.resolvedConfig = LaminHubSettings.resolve(config)

        log.debug 'Creating LaminHub client'
        this.hub = new LaminHub(
            resolvedConfig.supabaseApiUrl,
            resolvedConfig.supabaseAnonKey,
            config.apiKey
        )

        // Share the authenticated hub with the lamin:// file-system provider so that
        // artifact resolution reuses this connection and its instance cache.
        LaminConnection.getInstance().register(config, hub)

        log.debug 'Creating Lamin Instance client'
        this.laminInstance = getInstance(config.instanceOwner, config.instanceName)

        log.debug 'Testing connection to LaminDB instance'
        testConnection()
    }

    void initializeRun() {
        log.debug 'LaminRunManager.initializeRun'

        if (config.dryRun) {
            log.info 'nf-lamin: Dry-run mode enabled'
            return
        }

        try {
            resolveSpaceAndBranch()
            fetchOrCreateTransform()
            fetchOrCreateRun()
        } catch (Exception e) {
            log.error 'Failed to initialize run', e
            throw e
        }
    }

    /**
     * Never throws -- the reference must not break the status update it travels with, and
     * reflecting into another plugin can raise a linkage error rather than an exception.
     *
     * @return the `reference` / `reference_type` fields pointing at the Seqera Platform run,
     *   or an empty map if there is nothing to update
     */
    private Map<String, Object> runReferenceFields() {
        String reference
        try {
            reference = SeqeraPlatformHelper.resolveRunReference(session)
        }
        catch (Throwable e) {
            log.debug "Could not read the Seqera Platform watch URL: ${e.message}"
            return [:]
        }
        if (!reference || reference == run?.get('reference')) {
            return [:]
        }
        log.debug "Setting run.reference to '${reference}'"
        return [reference: reference, reference_type: 'Seqera'] as Map<String, Object>
    }

    void startRun() {
        log.debug 'LaminRunManager.startRun'
        if (run == null || laminInstance == null || session == null || config.dryRun) {
            return
        }

        WorkflowMetadata wfMetadata = session.getWorkflowMetadata()

        Map<String, Object> updateData = [
            started_at: wfMetadata.start,
            _status_code: RunStatus.STARTED.code
        ] as Map<String, Object>
        updateData.putAll(runReferenceFields())

        Map<String, Object> updatedRun = laminInstance.updateRecord(
            moduleName: 'core',
            modelName: 'run',
            uid: run.get('uid') as String,
            data: updateData
        )
        if (run.uid != updatedRun.uid) {
            log.warn "Run UID changed from ${run.uid} to ${updatedRun.uid} on start update!"
        }
        updateRun(updatedRun)
        log.info "Run ${updatedRun.get('uid')} ${RunStatus.STARTED.description}"
    }

    void testConnection() {
        if (laminInstance == null) {
            return
        }
        String instanceString = "${laminInstance.getOwner()}/${laminInstance.getName()}"
        try {
            Account account = laminInstance.getAccount()
            this.accountHandle = account.getHandle()
            log.info "→ connected lamindb: '${instanceString}' as '${account.getHandle()}'"
        } catch (ApiException e) {
            log.error "✗ Could not connect lamindb: '${instanceString}'!"
            log.error 'API call failed: ' + e.getMessage()
        }
    }

    /**
     * Resolve a UID-or-name reference to a record map.
     *
     * Named references use a prefix to indicate the resolution mode:
     * <ul>
     *   <li>{@code ?name} – look up by name; if not found, log a warning and return null (skip)</li>
     *   <li>{@code !name} – look up by name; if not found, throw an error</li>
     *   <li>{@code +name} – look up by name; if not found, create a new record</li>
     * </ul>
     * Values without a prefix are treated as UIDs and looked up via
     * {@link Instance#getRecord}.
     *
     * Results are cached to avoid repeated API calls for the same reference.
     *
     * @param moduleName The module name (e.g., 'core')
     * @param modelName The model name (e.g., 'project', 'ulabel', 'space', 'branch')
     * @param uidOrName The UID or prefixed name reference to resolve
     * @return the resolved record map, or null if the reference is null/empty or not found (in '?' mode)
     * @throws IllegalStateException if '!' mode is used and the record is not found
     */
    private Map resolveRecord(String moduleName, String modelName, String uidOrName) {
        if (!uidOrName) return null

        String cacheKey = "${moduleName}/${modelName}/${uidOrName}" as String
        Map cached = recordResolutionCache.get(cacheKey)
        if (cached != null) return cached

        Map record
        if (uidOrName.startsWith('+') || uidOrName.startsWith('!') || uidOrName.startsWith('?')) {
            char mode = uidOrName.charAt(0)
            String name = uidOrName.substring(1)
            log.debug "Resolving ${moduleName}.${modelName} by name: '${name}' (mode='${mode}')"

            if (mode == '+' as char) {
                record = laminInstance.findOrCreateByName(moduleName, modelName, name)
            } else {
                // '?' and '!' both look up only, differ in error handling
                record = laminInstance.findByName(moduleName, modelName, name)
                if (record == null) {
                    if (mode == '!' as char) {
                        throw new IllegalStateException(
                            "Required ${moduleName}.${modelName} with name '${name}' not found. " +
                            "Use '+${name}' to create it automatically, or '?${name}' to skip if missing."
                        )
                    } else {
                        // '?' mode – warn and skip
                        log.warn "Optional ${moduleName}.${modelName} with name '${name}' not found – skipping"
                    }
                }
            }
        } else {
            record = laminInstance.getRecord(
                moduleName: moduleName,
                modelName: modelName,
                idOrUid: uidOrName
            )
        }

        if (record != null) {
            recordResolutionCache.put(cacheKey, record)
            String resolvedUid = record.get('uid') as String
            String resolvedName = record.get('name') as String
            log.debug "Resolved ${moduleName}.${modelName} '${uidOrName}' → uid=${resolvedUid}, name=${resolvedName}"
        }
        return record
    }

    /**
     * Resolve a UID-or-name reference to a UID string.
     *
     * Convenience wrapper around {@link #resolveRecord} that returns just the UID.
     *
     * @param moduleName The module name (e.g., 'core')
     * @param modelName The model name (e.g., 'project', 'ulabel')
     * @param uidOrName The UID or '?name' reference to resolve
     * @return the resolved UID string, or null if not found
     */
    private String resolveRecordUid(String moduleName, String modelName, String uidOrName) {
        Map record = resolveRecord(moduleName, modelName, uidOrName)
        return record?.get('uid') as String
    }

    /**
     * Resolve space and branch to numeric IDs at initialization time.
     * These are cached and reused for all record creation operations.
     *
     * Supports both UID values and named references (e.g., '!my-space', '+my-branch').
     */
    void resolveSpaceAndBranch() {
        ensureInitialized('resolveSpaceAndBranch requires config and instance to be initialised')

        // Resolve space by UID or named reference
        if (config.spaceUid) {
            try {
                Map spaceRecord = resolveRecord('core', 'space', config.spaceUid)
                if (spaceRecord) {
                    resolvedSpaceId = (spaceRecord.get('id') as Number)?.intValue()
                    log.debug "Resolved space to id=${resolvedSpaceId} (uid=${spaceRecord.uid}, name=${spaceRecord.name})"
                } else {
                    log.warn "Could not resolve space '${config.spaceUid}'"
                }
            } catch (IllegalStateException e) {
                throw e
            } catch (Exception e) {
                log.error "Failed to resolve space '${config.spaceUid}': ${e.getMessage()}"
            }
        }

        // Resolve branch by UID or ?name
        if (config.branchUid) {
            try {
                Map branchRecord = resolveRecord('core', 'branch', config.branchUid)
                if (branchRecord) {
                    resolvedBranchId = (branchRecord.get('id') as Number)?.intValue()
                    log.debug "Resolved branch to id=${resolvedBranchId} (uid=${branchRecord.uid}, name=${branchRecord.name})"
                } else {
                    log.warn "Could not resolve branch '${config.branchUid}'"
                }
            } catch (IllegalStateException e) {
                throw e
            } catch (Exception e) {
                log.error "Failed to resolve branch '${config.branchUid}': ${e.getMessage()}"
            }
        }
    }

    Map<String, Object> fetchOrCreateTransform() {
        ensureInitialized('fetchOrCreateTransform requires session, config, and instance to be initialised')

        if (config.transformUid) {
            log.debug "Using manually specified transform UID: ${config.transformUid}"
            try {
                Map transformRecord = laminInstance.fetchTransform(config.transformUid)
                updateTransform(transformRecord)
                printTransformMessage(transformRecord, "Received transform ${transformRecord.get('uid')} from config")
                return transformRecord
            } catch (Exception e) {
                log.error "Failed to fetch transform with UID ${config.transformUid}: ${e.getMessage()}"
                log.warn 'Falling back to normal transform lookup/creation process'
            }
        }

        // Collect all relevant metadata
        TransformInfoHelper.TransformMetadata metadata = TransformInfoHelper.collect(session)

        // Generate transform key and version
        String key = TransformInfoHelper.generateTransformKey(metadata)
        String version = TransformInfoHelper.getEffectiveVersion(metadata)

        // Build filter for searching existing transforms
        List filterConditions = [[key: [eq: key]], [version_tag: [eq: version]]]

        log.debug "Searching for existing Transform with key ${key} and version_tag ${version}"
        List<Map> existingTransforms = laminInstance.findTransforms([and: filterConditions])
        log.debug "Found ${existingTransforms.size()} existing Transform(s) with key ${key} and version_tag ${version}"

        Map transformRecord = null
        if (existingTransforms) {
            if (existingTransforms.size() > 1) {
                log.warn "Found multiple Transform objects with key ${key} and version_tag ${version}"
            }
            transformRecord = existingTransforms[0]
            updateTransform(transformRecord)
            printTransformMessage(transformRecord, "Using existing transform ${transformRecord.get('uid')}")
            return transformRecord
        }

        if (config.dryRun) {
            // return a dummy object and print a message
            transformRecord = [
                uid: 'DrYrUnTrAuId',
                id: -1,
                key: key,
                version_tag: version
            ] as Map<String, Object>
            updateTransform(transformRecord)
            log.info "Dry-run mode: using dummy transform ${transformRecord.get('uid')}"
            return transformRecord
        }

        // Generate transform fields
        String sourceCode = TransformInfoHelper.generateTransformSourceCode(metadata)
        String description = TransformInfoHelper.generateTransformDescription(metadata)

        Map<String, Object> createArgs = [
            key: key,
            source_code: sourceCode,
            version_tag: version,
            kind: 'pipeline',
            reference: metadata.repository,
            reference_type: 'url',
            description: description
        ]

        transformRecord = laminInstance.createTransform(createArgs)

        // The /transforms endpoint does not accept certain fields on creation; update separately
        Map<String, Object> updateData = [:]
        if (resolvedSpaceId != null) updateData['space_id'] = resolvedSpaceId
        if (resolvedBranchId != null) {
            updateData['branch_id'] = resolvedBranchId
            updateData['created_on_id'] = resolvedBranchId
        }
        if (updateData) {
            transformRecord = laminInstance.updateRecord(
                moduleName: 'core',
                modelName: 'transform',
                uid: transformRecord.uid as String,
                data: updateData
            )
        }
        updateTransform(transformRecord)

        // Link transform to projects and ulabels from config
        List<String> transformProjectUids = mergeUidLists(config.getProjectUids())
        List<String> transformUlabelUids = mergeUidLists(config.getUlabelUids(), config.getTransformConfig()?.getUlabelUids())
        linkTransformToProjects(transformRecord, transformProjectUids)
        linkTransformToUlabels(transformRecord, transformUlabelUids)

        printTransformMessage(transformRecord, "Created new transform ${transformRecord.get('uid')}")
        return transformRecord
    }

    Map<String, Object> fetchOrCreateRun() {
        ensureInitialized('fetchOrCreateRun requires transform and instance to be initialised')

        if (config.runUid) {
            log.debug "Using manually specified run UID: ${config.runUid}"
            try {
                Map runRecord = laminInstance.fetchRun(config.runUid)

                Integer expectedTransformId = (transform?.get('id') as Number)?.intValue()
                Integer runTransformId = (runRecord.transform_id as Number)?.intValue()
                Integer statusCode = (runRecord._status_code as Number)?.intValue()
                if (expectedTransformId != runTransformId) {
                    log.warn "Run ${config.runUid} is associated with transform ${runTransformId} (expected ${expectedTransformId}). Creating a new run instead."
                } else if (statusCode != RunStatus.SCHEDULED.code) {
                    log.warn "Run ${config.runUid} has status code ${statusCode} (expected ${RunStatus.SCHEDULED.code} for SCHEDULED). Creating a new run instead."
                } else {
                    updateRun(runRecord)
                    printRunMessage(runRecord, "Received run ${runRecord.get('uid')} from config")
                    return runRecord
                }
            } catch (Exception e) {
                log.error "Failed to fetch run with UID ${config.runUid}: ${e.getMessage()}"
                log.warn 'Creating a new run instead'
            }
        }

        if (config.dryRun) {
            Map<String, Object> dummyRunRecord = [
                uid: 'DrYrUnRuNuId',
                id: -1,
                transform_id: (transform?.get('id') as Number)?.intValue(),
                _status_code: RunStatus.SCHEDULED.code
            ] as Map<String, Object>
            updateRun(dummyRunRecord)
            log.info "Dry-run mode: created dummy run ${dummyRunRecord.get('uid')}"
            return dummyRunRecord
        }

        WorkflowMetadata wfMetadata = session.getWorkflowMetadata()
        int transformId = (transform?.get('id') as Number)?.intValue()
        DateTimeFormatter isoMicros = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")
        String startIso = OffsetDateTime.ofInstant(wfMetadata.start.toInstant(), ZoneOffset.UTC).format(isoMicros)
        Map<String, Object> runData = [
                transform_id: transformId,
                name: wfMetadata.runName as String,
                created_at: startIso,
                started_at: startIso,
                _status_code: (int) RunStatus.SCHEDULED.code
            ]
        if (resolvedSpaceId != null) {
            runData.put('space_id', resolvedSpaceId)
        }
        if (resolvedBranchId != null) {
            runData.put('branch_id', resolvedBranchId)
            runData.put('created_on_id', resolvedBranchId)
        }
        Map<String, Object> runRecord = laminInstance.createRun(runData)
        updateRun(runRecord)

        // Link run to projects and ulabels from config
        List<String> runProjectUids = mergeUidLists(config.getProjectUids())
        List<String> runUlabelUids = mergeUidLists(config.getUlabelUids(), config.getRunConfig()?.getUlabelUids())
        linkRunToProjects(runRecord, runProjectUids)
        linkRunToUlabels(runRecord, runUlabelUids)

        printRunMessage(runRecord, 'Created new run')
        return runRecord
    }

    RunStatus determineRunStatus() {
        if (session.isSuccess()) {
            return RunStatus.COMPLETED
        } else if (session.isCancelled()) {
            return RunStatus.ABORTED
        } else if (session.getError() instanceof AbortSignalException) {
            // Sometimes a Ctrl+C or a cancellation from Seqera Platform might reach
            // Nextflow as an AbortSignalException rather than setting the cancelled flag.
            log.info "Run was cancelled by a termination signal (${session.getError().message}); marking run as aborted"
            return RunStatus.ABORTED
        } else {
            return RunStatus.ERRORED
        }
    }

    void finalizeRun() {
        if (run == null || laminInstance == null || session == null || config.dryRun) {
            return
        }
        RunStatus status = determineRunStatus()

        log.info "Run '${run.get('uid')}' ${status.description}"
        WorkflowMetadata wfMetadata = session.getWorkflowMetadata()

        // Update run with finish time, status, and report artifact
        Map<String, Object> updateData = [
            finished_at: wfMetadata.complete,
            _status_code: status.code
        ]

        // Handle report artifact
        Integer reportArtifactId = createReportArtifact()
        if (reportArtifactId != null) {
            updateData.put('report_id', reportArtifactId)
        }

        updateData.putAll(runReferenceFields())

        Map<String, Object> updatedRun = laminInstance.updateRecord(
            moduleName: 'core',
            modelName: 'run',
            uid: run.get('uid') as String,
            data: updateData
        )
        if (run.uid != updatedRun.uid) {
            log.warn "Run UID changed from ${run.uid} to ${updatedRun.uid} on final update!"
        }
        updateRun(updatedRun)
    }

    /**
     * Process explicitly configured artifact paths for a given direction.
     *
     * Collects all paths from the artifact configs (both global and
     * direction-specific) and creates artifacts using the existing
     * createInputArtifact / createOutputArtifact methods.
     *
     * Input paths should be processed at the beginning of the workflow
     * (onFlowBegin), and output paths at the end (before finalizeRun).
     *
     * @param direction 'input' or 'output'
     */
    void processConfigPathsAsync(String direction) {
        if (laminInstance == null || config == null || config.dryRun) {
            return
        }

        List<Map<String, Object>> pathEntries = []
        Map workflowParams = session.getParams() ?: [:]

        ArtifactConfig ac = resolveArtifactConfig(direction)
        if (ac != null) {
            pathEntries.addAll(ac.collectPaths(direction, workflowParams))
        }

        if (pathEntries.isEmpty()) {
            log.debug "No explicit ${direction} paths configured"
            return
        }

        log.info "Processing ${pathEntries.size()} configured ${direction} artifact path(s)"

        for (Map<String, Object> entry : pathEntries) {
            String pathStr = entry.path as String
            ArtifactEvaluation prebuiltEvaluation = entry.evaluation as ArtifactEvaluation
            submitToExecutor("configured ${direction} path '${pathStr}'") {
                try {
                    Path resolvedPath = FileHelper.asPath(pathStr)
                    log.debug "Resolved configured ${direction} path '${pathStr}' to ${resolvedPath.toUri()}"
                    if (direction == 'input') {
                        createInputArtifact(resolvedPath, prebuiltEvaluation)
                    } else {
                        createOutputArtifactFromConfigPaths(resolvedPath, prebuiltEvaluation)
                    }
                } catch (Exception e) {
                    log.warn "Failed to process configured ${direction} path '${pathStr}': ${e.message}"
                }
            }
        }
    }

    void createOutputArtifactOnFilePublishAsync(Path source, Path target, List<String> labels) {
        submitToExecutor("file-publish artifact for ${target}") {
            try {
                createOutputArtifactOnFilePublish(source, target, labels)
            } catch (Exception e) {
                logArtifactFailure("Failed to create output artifact for ${target}", e)
            }
        }
    }

    void createOutputArtifactOnWorkflowOutputAsync(Path path, String name) {
        submitToExecutor("workflow-output artifact for ${name}") {
            try {
                createOutputArtifactOnWorkflowOutput(path, name)
            } catch (Exception e) {
                logArtifactFailure("Failed to create output artifact for ${name}", e)
            }
        }
    }

    void createInputArtifactsAsync(String taskName, List<Path> sources) {
        submitToExecutor("input artifacts for task '${taskName}'") {
            try {
                for (Path source : sources) {
                    log.debug "LaminRunManager.createInputArtifactsAsync ${taskName}: '${source.toUri()}'"
                    createInputArtifact(source)
                }
            } catch (Exception e) {
                logArtifactFailure("Failed to create input artifacts for ${taskName}", e)
            }
        }
    }

    /**
     * Record metadata to attach to the artifact of a path, as requested from a workflow via
     * {@code annotateArtifact()}.
     *
     * The workflow and the publishing machinery run concurrently, so a path may be annotated
     * either before or after its artifact exists. Both orders are handled: the annotation is
     * remembered here and drained by {@link #recordArtifactForAnnotation}, and any artifact
     * already registered for the path is annotated right away.
     *
     * @param path       The file being annotated
     * @param annotation The metadata to attach
     */
    void registerAnnotation(Path path, ArtifactAnnotation annotation) {
        if (path == null || annotation == null || annotation.isEmpty()) {
            log.debug "Ignoring empty annotation for ${path}"
            return
        }
        if (laminInstance == null) {
            log.debug "No Lamin instance configured; ignoring annotation for ${path}"
            return
        }
        if (config?.dryRun) {
            log.info "Dry-run mode: would annotate ${path.toUri()} with ${annotation}"
            return
        }

        String key = PathUtils.toUriKey(path)
        if (key == null) {
            return
        }
        log.debug "Registering annotation for ${key}: ${annotation}"

        pendingAnnotations
            .computeIfAbsent(key, { Collections.synchronizedList(new ArrayList<ArtifactAnnotation>()) })
            .add(annotation)

        List<Map<String, Object>> artifacts = artifactsByAnnotationKey.get(key)
        if (artifacts == null) {
            return
        }
        // The artifact already exists, so apply straight away - on a worker thread, since this
        // runs on a dataflow operator thread that must not block on API calls
        matchedAnnotationKeys.add(key)
        List<Map<String, Object>> snapshot
        synchronized (artifacts) {
            snapshot = new ArrayList<Map<String, Object>>(artifacts)
        }
        for (Map<String, Object> artifact : snapshot) {
            submitToExecutor("annotation for ${key}") {
                try {
                    applyAnnotation(artifact, annotation)
                } catch (Exception e) {
                    logArtifactFailure("Failed to annotate artifact ${artifact.get('uid')}", e)
                }
            }
        }
    }

    /**
     * Register an artifact under the paths that may have been annotated for it, and apply any
     * annotation already requested for those paths.
     *
     * Runs on the artifact executor (the callers create artifacts there), so annotations are
     * applied inline rather than submitted again.
     *
     * @param artifact The artifact map (must contain 'id' and 'uid')
     * @param paths    Paths that resolve to this artifact; nulls are ignored
     */
    private void recordArtifactForAnnotation(Map<String, Object> artifact, Path... paths) {
        if (artifact == null || paths == null) {
            return
        }
        for (Path path : paths) {
            String key = PathUtils.toUriKey(path)
            if (key == null) {
                continue
            }
            artifactsByAnnotationKey
                .computeIfAbsent(key, { Collections.synchronizedList(new ArrayList<Map<String, Object>>()) })
                .add(artifact)

            List<ArtifactAnnotation> annotations = pendingAnnotations.get(key)
            if (annotations == null) {
                continue
            }
            matchedAnnotationKeys.add(key)
            List<ArtifactAnnotation> snapshot
            synchronized (annotations) {
                snapshot = new ArrayList<ArtifactAnnotation>(annotations)
            }
            for (ArtifactAnnotation annotation : snapshot) {
                try {
                    applyAnnotation(artifact, annotation)
                } catch (Exception e) {
                    logArtifactFailure("Failed to annotate artifact ${artifact.get('uid')}", e)
                }
            }
        }
    }

    /**
     * Attach an annotation's metadata to an artifact.
     *
     * Every operation is an idempotent PATCH or upsert, so re-applying an annotation - which
     * happens when a path is annotated both before and after its artifact was created - is
     * harmless.
     *
     * @param artifact   The artifact map (must contain 'id' and 'uid')
     * @param annotation The metadata to attach
     */
    private void applyAnnotation(Map<String, Object> artifact, ArtifactAnnotation annotation) {
        if (artifact == null || annotation == null || laminInstance == null) {
            return
        }
        String artifactUid = artifact.get('uid') as String
        if (!artifactUid) {
            log.warn "Cannot annotate artifact without a uid: ${artifact}"
            return
        }

        Map<String, Object> updates = [:] as Map<String, Object>
        if (annotation.kind) {
            updates.put('kind', annotation.kind)
        }
        if (annotation.description) {
            updates.put('description', annotation.description)
        }
        if (updates) {
            try {
                laminInstance.updateRecord(
                    moduleName: 'core',
                    modelName: 'artifact',
                    uid: artifactUid,
                    data: updates
                )
                log.debug "Updated artifact ${artifactUid} with ${updates}"
            } catch (Exception e) {
                log.warn "Could not update artifact ${artifactUid} with ${updates}: ${e.getMessage()}"
            }
        }

        linkArtifactToUlabels(artifact, annotation.ulabelUids)
        linkArtifactToProjects(artifact, annotation.projectUids)
    }

    /**
     * Warn about annotations that never matched an artifact, i.e. files that were annotated but
     * never published or otherwise tracked. Called once all artifact tasks have completed.
     */
    void warnUnmatchedAnnotations() {
        List<String> unmatched = pendingAnnotations.keySet().findAll { !matchedAnnotationKeys.contains(it) }.toList()
        if (!unmatched) {
            return
        }
        log.warn "annotateArtifact was called for ${unmatched.size()} path(s) that were not tracked as artifacts, " +
            "so their annotations were not applied: ${unmatched.join(', ')}"
    }

    /**
     * Log a failure from an async artifact-creation task. A permission-denied error
     * (a 403 from the API) is rendered with the connected account handle so the
     * message is actionable; anything else is logged as-is.
     */
    private void logArtifactFailure(String context, Exception e) {
        PermissionDeniedException pde = findPermissionDeniedException(e)
        if (pde != null) {
            log.error "${context}: ${pde.describe(accountHandle)}"
        } else {
            log.error "${context}: ${e.message}", e
        }
    }

    private static PermissionDeniedException findPermissionDeniedException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof PermissionDeniedException) {
                return (PermissionDeniedException) cur
            }
        }
        return null
    }

    private void submitToExecutor(String description, Runnable task) {
        if (artifactExecutor.isShutdown()) {
            log.warn "Artifact executor is shut down; skipping: ${description}"
            return
        }
        artifactExecutor.submit(task)
    }

    void awaitArtifactTasks() {
        artifactExecutor.shutdown()
        try {
            if (!artifactExecutor.awaitTermination(1, TimeUnit.HOURS)) {
                log.warn "Lamin artifact tasks did not complete within timeout; some artifacts may not have been registered"
            }
        } catch (InterruptedException e) {
            log.warn "Interrupted while waiting for artifact tasks to complete"
            Thread.currentThread().interrupt()
        } finally {
            artifactLocks.clear()
        }
    }

    /**
     * Evaluate an artifact path against configuration rules.
     *
     * Returns a combined result with tracking decision and accumulated metadata.
     * Uses either the global 'artifacts' config OR the direction-specific config
     * (input_artifacts/output_artifacts), as they are mutually exclusive.
     *
     * @param path File path to evaluate
     * @param direction 'input' or 'output'
     * @return ArtifactEvaluation with shouldTrack flag and metadata
     */
    ArtifactEvaluation evaluateArtifact(Path path, String direction) {
        if (config == null) {
            // Default to tracking with empty metadata if no config
            return new ArtifactEvaluation(true, [], null)
        }

        // Resolve the effective artifact config for this direction
        ArtifactConfig artifactConfig = resolveArtifactConfig(direction)

        // If no config defined, default to tracking with empty metadata
        if (artifactConfig == null) {
            log.debug "No artifact config defined, tracking '${path.toUri()}' as ${direction} with default settings"
            return new ArtifactEvaluation(true, [], null)
        }

        // Evaluate the path against the config
        ArtifactEvaluation evaluation = artifactConfig.evaluate(path, direction, session.getParams() ?: [:])
        if (evaluation.shouldTrack) {
            log.debug "Artifact '${path.toUri()}' will be tracked as ${direction} with evaluation: ${evaluation}"
        } else {
            log.debug "Artifact '${path.toUri()}' excluded by artifact config"
        }
        return evaluation
    }

    Map<String, Object> createInputArtifact(Path path) {
        return createInputArtifact(path, null)
    }

    Map<String, Object> createInputArtifact(Path path, ArtifactEvaluation prebuiltEvaluation) {
        if (laminInstance == null || config.dryRun) {
            return null
        }

        // Check path-type exclusions (local, workdir, assets) based on config
        if (shouldSkipArtifact(path, 'input')) {
            return null
        }

        // Use pre-built evaluation if provided, otherwise evaluate
        ArtifactEvaluation evaluation = prebuiltEvaluation ?: evaluateArtifact(path, 'input')
        if (!evaluation.shouldTrack) {
            log.debug "Skipping input artifact creation for ${path.toUri()} (excluded by config)"
            return null
        }

        String description = "Input artifact at ${path.toUri()}"
        if (evaluation.descriptionConfig != null) {
            Map<String, Object> descContext = [runId: null, path: path, outputName: null] as Map<String, Object>
            String resolved = ConfigUtils.resolveDescription(evaluation.descriptionConfig, descContext)
            if (resolved != null) {
                description = resolved
            }
        }

        Map<String, Object> params = [
            path: path,
            description: description
        ]
        if (evaluation.kind) {
            params.kind = evaluation.kind
        }

        Map<String, Object> artifact = fetchOrCreateArtifact(params)

        if (artifact == null) {
            log.warn "Failed to fetch or create input artifact for path ${path.toUri()}"
            return null
        }

        log.debug "Using input artifact ${artifact?.get('uid')} for path ${path.toUri()}"

        // Link artifact to run and metadata
        linkInputArtifactToRun(artifact)
        List<String> artifactProjectUids = mergeUidLists(config.getProjectUids())
        List<String> artifactUlabelUids = mergeUidLists(config.getUlabelUids(), evaluation.ulabel_uids)
        linkArtifactToProjects(artifact, artifactProjectUids)
        linkArtifactToUlabels(artifact, artifactUlabelUids)

        // A lamin:// input is annotated under the URI the workflow holds, but registered under
        // the storage path it resolves to, so record both
        Path storagePath = path instanceof LaminPath ? ((LaminPath) path).resolveToStorage() : null
        recordArtifactForAnnotation(artifact, path, storagePath)

        return artifact
    }

    /**
     * Called from {@link ai.lamin.nf_lamin.LaminObserver#onFilePublish}.
     *
     * If the artifact was already created by {@code createOutputArtifactOnWorkflowOutput}
     * (which fires first for index/manifest files), only the labels are linked to the
     * existing artifact rather than creating a duplicate.
     *
     * @param source The path the file was published from ({@code FilePublishEvent.source}), which
     *               is the path the workflow itself holds and can annotate (may be null)
     * @param path   The published file path ({@code FilePublishEvent.target})
     * @param labels Labels from the publishDir {@code label} directive (may be null or empty)
     */
    Map<String, Object> createOutputArtifactOnFilePublish(Path source, Path path, List<String> labels) {
        String pathKey = PathUtils.toUriKey(path)
        Map<String, Object> cachedArtifact = publishedArtifactsByPath.get(pathKey) as Map<String, Object>
        if (cachedArtifact != null) {
            if (labels && config?.features?.use_output_labels != false) {
                linkArtifactToUlabels(cachedArtifact, labels.collect { "+${it}" as String })
            }
            // The source is only known here, so register it even when the artifact already exists
            recordArtifactForAnnotation(cachedArtifact, source)
            return cachedArtifact
        }
        return createOutputArtifact(path, null, labels, pendingOutputNames.remove(pathKey), source)
    }

    /**
     * Record the output name of an index file, to be used when it is published.
     *
     * Called from {@link ai.lamin.nf_lamin.LaminObserver#onWorkflowOutput}. The index file is
     * announced before Nextflow writes it, and rewritten on a re-run, so it can only be
     * registered from the {@code onFilePublish} that follows.
     *
     * @param path       The index file path ({@code WorkflowOutputEvent.index})
     * @param outputName The workflow output block name
     */
    void rememberOutputName(Path path, String outputName) {
        if (path != null && outputName != null) {
            pendingOutputNames.put(PathUtils.toUriKey(path), outputName)
        }
    }

    /**
     * Called for paths declared explicitly in the {@code lamin.artifacts} config block.
     *
     * @param path       The output file path
     * @param evaluation Pre-built evaluation carrying kind, key, ulabels, and description config
     */
    Map<String, Object> createOutputArtifactFromConfigPaths(Path path, ArtifactEvaluation evaluation) {
        return createOutputArtifact(path, evaluation, null, null, null)
    }

    /**
     * Called from {@link ai.lamin.nf_lamin.LaminObserver#onWorkflowOutput}.
     *
     * Associates a path with its named workflow output. If the artifact was already created
     * by {@code createOutputArtifactOnFilePublish} (normal content files where
     * {@code onFilePublish} fires first), this is a no-op. Otherwise the artifact is created now so that paths not
     * published through a {@code publishDir} are still recorded.
     *
     * @param path       The published file path ({@code WorkflowOutputEvent.value} or {@code .index})
     * @param outputName The workflow output block name ({@code WorkflowOutputEvent.name})
     */
    void createOutputArtifactOnWorkflowOutput(Path path, String outputName) {
        if (run == null || laminInstance == null || config?.dryRun) {
            return
        }
        String pathKey = PathUtils.toUriKey(path)
        if (!publishedArtifactsByPath.containsKey(pathKey)) {
            // Path was not captured via onFilePublish – create it now so it is still recorded.
            Map<String, Object> artifact = createOutputArtifact(path, null, null, outputName, null)
            if (artifact == null) {
                log.debug "No artifact tracked for workflow output '${outputName}' at ${pathKey}"
            }
        }
    }

    private Map<String, Object> createOutputArtifact(Path path, ArtifactEvaluation prebuiltEvaluation, List<String> labels, String outputName, Path source) {
        if (run == null || laminInstance == null || config.dryRun) {
            return null
        }

        // Check path-type exclusions (local) based on config
        if (shouldSkipArtifact(path, 'output')) {
            return null
        }

        // Use pre-built evaluation if provided, otherwise evaluate
        ArtifactEvaluation evaluation = prebuiltEvaluation ?: evaluateArtifact(path, 'output')
        if (!evaluation.shouldTrack) {
            log.debug "Skipping output artifact creation for ${path.toUri()} (excluded by config)"
            return null
        }

        Integer runId = (run.get('id') as Number)?.intValue()
        if (runId == null) {
            return null
        }

        String outputStr = outputName ? " '${outputName}'" : ""
        String defaultDescription = "Output artifact${outputStr} for run ${runId}"
        String description = defaultDescription
        if (evaluation.descriptionConfig != null) {
            Map<String, Object> descContext = [runId: runId, path: path, outputName: outputName] as Map<String, Object>
            String resolved = ConfigUtils.resolveDescription(evaluation.descriptionConfig, descContext)
            if (resolved != null) {
                description = resolved
            }
        }

        Map<String, Object> params = [
            path: path,
            run_id: runId,
            description: description
        ]
        if (evaluation.kind) {
            params.kind = evaluation.kind
        }

        Map<String, Object> artifact = fetchOrCreateArtifact(params)

        if (artifact == null) {
            log.warn "Failed to create output artifact for path ${path.toUri()}"
            return null
        }

        // Link artifact metadata (projects, ulabels) - run is already linked via run_id
        List<String> artifactProjectUids = mergeUidLists(config.getProjectUids())
        List<String> artifactUlabelUids = mergeUidLists(config.getUlabelUids(), evaluation.ulabel_uids)
        linkArtifactToProjects(artifact, artifactProjectUids)
        linkArtifactToUlabels(artifact, artifactUlabelUids)

        // Link output labels as ULabels if the feature is enabled
        if (labels && config?.features?.use_output_labels != false) {
            linkArtifactToUlabels(artifact, labels.collect { "+${it}" as String })
        }

        // Cache so trackWorkflowOutput can find the artifact without creating a duplicate
        publishedArtifactsByPath.put(PathUtils.toUriKey(path), artifact)

        // Apply any annotations the workflow requested for this file, under both the path it
        // was published from (what the workflow holds) and the path it was published to
        recordArtifactForAnnotation(artifact, source, path)

        return artifact
    }

    /**
     * Link an input artifact to the current run.
     *
     * @param artifact The artifact map (must contain 'id' and 'uid')
     */
    private void linkInputArtifactToRun(Map<String, Object> artifact) {
        if (artifact == null || laminInstance == null) {
            return
        }

        Integer artifactId = (artifact.get('id') as Number)?.intValue()
        String artifactUid = artifact.get('uid') as String
        Integer runId = (run?.get('id') as Number)?.intValue()

        if (artifactId == null) {
            log.warn "Artifact ID is null for artifact ${artifactUid}"
            return
        }

        if (runId == null) {
            log.warn "Run ID is null; cannot link artifact ${artifactUid} to run"
            return
        }

        upsertLink(
            'artifact_input_of_runs',
            [artifact_id: artifactId, run_id: runId] as Map<String, Object>,
            ['artifact_id', 'run_id'],
            "artifact ${artifactUid} as input to run ${run.get('uid')}"
        )
    }

    /**
     * Idempotently create a link record by upserting on its unique key.
     *
     * <p>For link tables with a nullable feature column (e.g. {@code artifactproject}),
     * the unique constraint treats nulls as non-distinct, so {@code feature_id: null}
     * must be part of the record and the conflict key.
     *
     * @param modelName The link model name (e.g. 'artifactproject')
     * @param data The link record fields
     * @param conflictColumns The columns of the model's unique constraint
     * @param description Human-readable description for logging, e.g. "artifact x to project y"
     */
    private void upsertLink(String modelName, Map<String, Object> data, List<String> conflictColumns, String description) {
        try {
            laminInstance.upsertRecord(
                moduleName: 'core',
                modelName: modelName,
                data: data,
                conflictColumns: conflictColumns
            )
            log.debug "Linked ${description}"
        } catch (Exception e) {
            log.warn "Could not link ${description}: ${e.getMessage()}"
        }
    }

    /**
     * Link artifact to projects.
     *
     * @param artifact The artifact map (must contain 'id' and 'uid')
     * @param projectUids List of project UIDs or '?name' references to link
     */
    private void linkArtifactToProjects(Map<String, Object> artifact, List<String> projectUids) {
        if (artifact == null || laminInstance == null || !projectUids) {
            return
        }

        Integer artifactId = (artifact.get('id') as Number)?.intValue()
        String artifactUid = artifact.get('uid') as String

        if (artifactId == null) {
            log.warn "Artifact ID is null for artifact ${artifactUid}"
            return
        }

        for (String projectUid : projectUids) {
            try {
                // Look up project by UID or ?name to get numeric ID
                Map<String, Object> project = resolveRecord('core', 'project', projectUid)
                Integer projectId = (project?.get('id') as Number)?.intValue()
                if (projectId == null) {
                    log.warn "Could not find project '${projectUid}'"
                    continue
                }

                upsertLink(
                    'artifactproject',
                    [artifact_id: artifactId, project_id: projectId, feature_id: null] as Map<String, Object>,
                    ['artifact_id', 'feature_id', 'project_id'],
                    "artifact ${artifactUid} to project ${projectUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link artifact ${artifactUid} to project ${projectUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Link artifact to ulabels.
     *
     * @param artifact The artifact map (must contain 'id' and 'uid')
     * @param ulabelUids List of ulabel UIDs or '?name' references to link
     */
    private void linkArtifactToUlabels(Map<String, Object> artifact, List<String> ulabelUids) {
        if (artifact == null || laminInstance == null || !ulabelUids) {
            return
        }

        Integer artifactId = (artifact.get('id') as Number)?.intValue()
        String artifactUid = artifact.get('uid') as String

        if (artifactId == null) {
            log.warn "Artifact ID is null for artifact ${artifactUid}"
            return
        }

        for (String ulabelUid : ulabelUids) {
            try {
                // Look up ulabel by UID or ?name to get numeric ID
                Map<String, Object> ulabel = resolveRecord('core', 'ulabel', ulabelUid)
                Integer ulabelId = (ulabel?.get('id') as Number)?.intValue()
                if (ulabelId == null) {
                    log.warn "Could not find ulabel '${ulabelUid}'"
                    continue
                }

                upsertLink(
                    'artifactulabel',
                    [artifact_id: artifactId, ulabel_id: ulabelId, feature_id: null] as Map<String, Object>,
                    ['artifact_id', 'feature_id', 'ulabel_id'],
                    "artifact ${artifactUid} to ulabel ${ulabelUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link artifact ${artifactUid} to ulabel ${ulabelUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Merge multiple UID lists into a single deduplicated list.
     *
     * @param lists Variable number of UID lists to merge
     * @return Combined list with duplicates removed
     */
    private static List<String> mergeUidLists(List<String>... lists) {
        Set<String> merged = new LinkedHashSet<>()
        for (List<String> list : lists) {
            if (list != null) {
                merged.addAll(list)
            }
        }
        return merged.toList()
    }

    /**
     * Resolve the effective ArtifactConfig for a given direction.
     *
     * Checks the shared 'artifacts' config first, then falls back to
     * direction-specific config (input_artifacts / output_artifacts).
     *
     * @param direction 'input' or 'output'
     * @return the ArtifactConfig to use, or null if none is configured
     */
    private ArtifactConfig resolveArtifactConfig(String direction) {
        if (config == null) {
            return null
        }
        ArtifactConfig ac = config.getArtifacts()
        if (ac != null) {
            return ac
        }
        return direction == 'input' ? config.getInputArtifacts() : config.getOutputArtifacts()
    }

    /**
     * Check whether an artifact should be skipped based on its path type
     * and the resolved config values for exclude_work_dir and exclude_assets_dir.
     *
     * Defaults when no config is present:
     *   exclude_work_dir   = true
     *   exclude_assets_dir = true
     *
     * @param path The artifact path
     * @param direction 'input' or 'output'
     * @return true if the artifact should be skipped
     */
    private boolean shouldSkipArtifact(Path path, String direction) {
        ArtifactConfig ac = resolveArtifactConfig(direction)
        boolean excludeWorkDir   = ac != null ? ac.exclude_work_dir   : true
        boolean excludeAssetsDir = ac != null ? ac.exclude_assets_dir : true


        if (excludeWorkDir && isInWorkDir(path)) {
            log.debug "Skipping ${direction} artifact creation for workdir file at ${path.toUri()} (exclude_work_dir=true)"
            return true
        }

        if (excludeAssetsDir && isInAssetsDir(path)) {
            log.debug "Skipping ${direction} artifact creation for assets file at ${path.toUri()} (exclude_assets_dir=true)"
            return true
        }

        if (isLocalPath(path)) {
            if (!localFileWarningShown) {
                localFileWarningShown = true
                log.warn "Local file detected at ${path.toUri()}. nf-lamin only tracks remote paths (s3://, gs://, etc.). Local files will be ignored. This warning is only shown once per session."
            }
            log.debug "Skipping ${direction} artifact creation for local file at ${path.toUri()}"
            return true
        }

        return false
    }

    private boolean isLocalPath(Path path) {
        return (path.toUri().getScheme() ?: 'file') == 'file'
    }

    /**
     * Check if a path is within the workdir.
     */
    private boolean isInWorkDir(Path path) {
        if (session == null) {
            return false
        }
        Path workDir = session.workDir
        if (workDir == null) {
            return false
        }
        return isInDir(path, workDir, 'workdir')
    }

    /**
     * Check if a path is within the Nextflow assets directory.
     *
     * Respects NXF_ASSETS and NXF_HOME env vars via nextflow.Const.DEFAULT_ROOT.
     */
    private boolean isInAssetsDir(Path path) {
        File assetsRoot = Const.DEFAULT_ROOT
        if (assetsRoot == null) {
            return false
        }
        Path assetsDir = assetsRoot.toPath()
        return isInDir(path, assetsDir, 'assets')
    }

    /**
     * Check whether a path lives under a base directory, handling different filesystems.
     */
    private boolean isInDir(Path path, Path baseDir, String label) {
        if (path == null || baseDir == null) {
            return false
        }

        if (path.getFileSystem() == baseDir.getFileSystem()) {
            try {
                Path normalizedPath = path.toAbsolutePath().normalize()
                Path normalizedBase = baseDir.toAbsolutePath().normalize()
                return normalizedPath.startsWith(normalizedBase)
            } catch (Exception e) {
                log.debug "Error checking if ${path.toUri()} is in ${label}: ${e.message}"
                return false
            }
        }

        try {
            String pathUri = PathUtils.toUriKey(path)
            String baseUri = PathUtils.toUriKey(baseDir)

            if (!baseUri.endsWith('/')) {
                baseUri += '/'
            }

            return pathUri.startsWith(baseUri)
        } catch (Exception e) {
            log.debug "Error comparing URIs for ${path.toUri()} and ${label}: ${e.message}"
            return false
        }
    }

    /**
     * Link transform to projects.
     *
     * @param transformRecord The transform map (must contain 'id' and 'uid')
     * @param projectUids List of project UIDs or '?name' references to link
     */
    private void linkTransformToProjects(Map<String, Object> transformRecord, List<String> projectUids) {
        if (transformRecord == null || laminInstance == null || !projectUids) {
            return
        }

        Integer transformId = (transformRecord.get('id') as Number)?.intValue()
        String transformUid = transformRecord.get('uid') as String

        if (transformId == null) {
            log.warn "Transform ID is null for transform ${transformUid}"
            return
        }

        for (String projectUid : projectUids) {
            try {
                // Look up project by UID or ?name to get numeric ID
                Map<String, Object> project = resolveRecord('core', 'project', projectUid)
                Integer projectId = (project?.get('id') as Number)?.intValue()
                if (projectId == null) {
                    log.warn "Could not find project '${projectUid}'"
                    continue
                }

                upsertLink(
                    'transformproject',
                    [transform_id: transformId, project_id: projectId] as Map<String, Object>,
                    ['project_id', 'transform_id'],
                    "transform ${transformUid} to project ${projectUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link transform ${transformUid} to project ${projectUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Link transform to ulabels.
     *
     * @param transformRecord The transform map (must contain 'id' and 'uid')
     * @param ulabelUids List of ulabel UIDs or '?name' references to link
     */
    private void linkTransformToUlabels(Map<String, Object> transformRecord, List<String> ulabelUids) {
        if (transformRecord == null || laminInstance == null || !ulabelUids) {
            return
        }

        Integer transformId = (transformRecord.get('id') as Number)?.intValue()
        String transformUid = transformRecord.get('uid') as String

        if (transformId == null) {
            log.warn "Transform ID is null for transform ${transformUid}"
            return
        }

        for (String ulabelUid : ulabelUids) {
            try {
                // Look up ulabel by UID or ?name to get numeric ID
                Map<String, Object> ulabel = resolveRecord('core', 'ulabel', ulabelUid)
                Integer ulabelId = (ulabel?.get('id') as Number)?.intValue()
                if (ulabelId == null) {
                    log.warn "Could not find ulabel '${ulabelUid}'"
                    continue
                }

                upsertLink(
                    'transformulabel',
                    [transform_id: transformId, ulabel_id: ulabelId] as Map<String, Object>,
                    ['transform_id', 'ulabel_id'],
                    "transform ${transformUid} to ulabel ${ulabelUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link transform ${transformUid} to ulabel ${ulabelUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Link run to projects.
     *
     * @param runRecord The run map (must contain 'id' and 'uid')
     * @param projectUids List of project UIDs or '?name' references to link
     */
    private void linkRunToProjects(Map<String, Object> runRecord, List<String> projectUids) {
        if (runRecord == null || laminInstance == null || !projectUids) {
            return
        }

        Integer runId = (runRecord.get('id') as Number)?.intValue()
        String runUid = runRecord.get('uid') as String

        if (runId == null) {
            log.warn "Run ID is null for run ${runUid}"
            return
        }

        for (String projectUid : projectUids) {
            try {
                // Look up project by UID or ?name to get numeric ID
                Map<String, Object> project = resolveRecord('core', 'project', projectUid)
                Integer projectId = (project?.get('id') as Number)?.intValue()
                if (projectId == null) {
                    log.warn "Could not find project '${projectUid}'"
                    continue
                }

                upsertLink(
                    'runproject',
                    [run_id: runId, project_id: projectId] as Map<String, Object>,
                    ['project_id', 'run_id'],
                    "run ${runUid} to project ${projectUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link run ${runUid} to project ${projectUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Link run to ulabels.
     *
     * @param runRecord The run map (must contain 'id' and 'uid')
     * @param ulabelUids List of ulabel UIDs or '?name' references to link
     */
    private void linkRunToUlabels(Map<String, Object> runRecord, List<String> ulabelUids) {
        if (runRecord == null || laminInstance == null || !ulabelUids) {
            return
        }

        Integer runId = (runRecord.get('id') as Number)?.intValue()
        String runUid = runRecord.get('uid') as String

        if (runId == null) {
            log.warn "Run ID is null for run ${runUid}"
            return
        }

        for (String ulabelUid : ulabelUids) {
            try {
                // Look up ulabel by UID or ?name to get numeric ID
                Map<String, Object> ulabel = resolveRecord('core', 'ulabel', ulabelUid)
                Integer ulabelId = (ulabel?.get('id') as Number)?.intValue()
                if (ulabelId == null) {
                    log.warn "Could not find ulabel '${ulabelUid}'"
                    continue
                }

                upsertLink(
                    'runulabel',
                    [run_id: runId, ulabel_id: ulabelId] as Map<String, Object>,
                    ['run_id', 'ulabel_id'],
                    "run ${runUid} to ulabel ${ulabelUid}"
                )
            } catch (Exception e) {
                log.warn "Could not link run ${runUid} to ulabel ${ulabelUid}: ${e.getMessage()}"
            }
        }
    }

    /**
     * Fetch an existing artifact by its remote path.
     * @param remotePath The remote storage path of the artifact (e.g., s3://bucket/file.txt)
     * @return the artifact map if found, null otherwise
     */
    private Map<String, Object> fetchArtifact(String remotePath) {
        if (remotePath == null || laminInstance == null) {
            return null
        }

        Map<String, Object> artifact = laminInstance.getArtifactByPath(remotePath)
        if (artifact != null) {
            log.debug "Found existing artifact at ${remotePath}: ${artifact.get('uid')}"
        }
        return artifact
    }

    /**
     * Create or fetch an artifact at the specified path.
     * @param params A map of parameters:
     *   - path (Path, required): The local or remote path of the artifact
     *   - run_id (Integer, optional): The ID of the run to associate the artifact with
     *   - description (String, optional): A description for the artifact
     *   - kind (String, optional): The artifact kind (e.g., 'dataset', 'model')
     *   - key (String, optional): The artifact key (a human-readable identifier). Defaults to the filename.
     * @return the artifact map if created or found, null on failure
     */
    Map<String, Object> fetchOrCreateArtifact(Map<String, Object> params) {
        if (laminInstance == null || config.dryRun) {
            return null
        }

        // Validate and extract required parameter
        if (!params.get('path')) {
            throw new IllegalArgumentException("Required parameter 'path' is missing")
        }

        Path path = params.get('path') as Path
        if (path == null) {
            throw new IllegalArgumentException("Parameter 'path' must be a valid Path object")
        }

        // If path is a LaminPath, resolve it to the underlying storage path
        if (path instanceof LaminPath) {
            path = ((LaminPath) path).resolveToStorage()
        }

        // A file published to a lamin:// target lives in the storage location the target
        // resolved to, and that location decides the space when it has one
        Integer spaceId = resolvedSpaceId
        if (path instanceof LaminS3Path) {
            LaminStorageTarget target = ((LaminS3FileSystem) path.fileSystem).target
            if (target?.spaceId != null) {
                spaceId = target.spaceId
            }
        }

        // Validate and extract optional parameters
        Integer runId = null
        if (params.containsKey('run_id')) {
            Object runIdValue = params.get('run_id')
            if (runIdValue != null && !(runIdValue instanceof Integer)) {
                throw new IllegalArgumentException("Parameter 'run_id' must be an Integer or null")
            }
            runId = runIdValue as Integer
        }

        String description = null
        if (params.containsKey('description')) {
            Object descValue = params.get('description')
            if (descValue != null && !(descValue instanceof String)) {
                throw new IllegalArgumentException("Parameter 'description' must be a String or null")
            }
            description = descValue as String
        }

        String kind = null
        if (params.containsKey('kind')) {
            Object kindValue = params.get('kind')
            if (kindValue != null && !(kindValue instanceof String)) {
                throw new IllegalArgumentException("Parameter 'kind' must be a String or null")
            }
            kind = kindValue as String
        }

        if ((path.toUri().getScheme() ?: 'file') == 'file') {
            log.debug "Skipping artifact creation for local file at ${path.toUri()} (local files are not tracked)"
            return null
        }

        String logContext = runId != null ? "for run ${runId}" : "without run association"
        String pathStr = PathUtils.toStorageUri(path)
        Map<String, Object> artifact = null
        ReentrantLock pathLock = artifactLocks.computeIfAbsent(pathStr) { new ReentrantLock() }
        pathLock.lock()
        try {
            log.debug "Creating artifact ${logContext} at ${path.toUri()}"

            Map<String, Object> apiParams = [:]
            if (runId != null) {
                apiParams.put('run_id', runId)
            }
            if (description != null) {
                apiParams.put('description', description)
            }
            if (kind != null) {
                apiParams.put('kind', kind)
            }
            if (spaceId != null) {
                apiParams.put('space_id', spaceId)
            }
            if (resolvedBranchId != null) {
                apiParams.put('branch_id', resolvedBranchId)
            }

            apiParams.put('path', pathStr)
            artifact = laminInstance.createArtifact(apiParams)
        } catch (Exception e) {
            log.error "Failed to create artifact ${logContext} at ${path.toUri()}"
            log.debug "Exception: ${e.getMessage()}", e
            return null
        } finally {
            pathLock.unlock()
        }

        Number artifactRunNumber = ((artifact.get('run') ?: artifact.get('run_id')) as Number)
        boolean isNewArtifact = runId == null || (artifactRunNumber != null && artifactRunNumber.intValue() == runId)
        String verb = isNewArtifact ? 'Created' : 'Detected previous'
        String webUrl = resolvedConfig != null ? resolvedConfig.webUrl : null
        String owner = laminInstance.getOwner()
        String name = laminInstance.getName()
        String artifactUid = artifact.get('uid') as String
        String webUrlStr = webUrl ? " (${webUrl}/${owner}/${name}/artifact/${artifactUid})" : ""
        log.debug "${verb} artifact ${artifactUid}${webUrlStr}"
        return artifact
    }

    private Integer createReportArtifact() {
        Map reportConfig = session.config.navigate("report") as Map
        log.debug "Report config: ${reportConfig}"

        // Determine the report path (either existing file or generate placeholder)
        boolean reportEnabled = reportConfig?.get('enabled') as Boolean ?: false
        boolean hasReportFile = reportEnabled && reportConfig?.get('file')

        Path reportPath = null
        Path tempReportPath = null
        boolean isPlaceholder = false

        if (hasReportFile) {
            // Use existing report file
            reportPath = FileHelper.asPath(reportConfig.get('file') as String)
            log.debug "Report enabled, using file: ${reportPath}"
        } else {
            // Generate placeholder HTML
            log.debug "Report not enabled, generating placeholder HTML"
            tempReportPath = Files.createTempFile("nextflow-report-", ".html")
            reportPath = tempReportPath
            isPlaceholder = true

            String placeholderHtml = """<!DOCTYPE html>
<html>
<head>
    <title>Nextflow Report Not Generated</title>
    <style>
        body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }
        .info-box { background: #f0f0f0; border-left: 4px solid #007acc; padding: 20px; }
        code { background: #e8e8e8; padding: 2px 6px; border-radius: 3px; }
        pre { background: #f5f5f5; padding: 15px; border-radius: 5px; overflow-x: auto; }
    </style>
</head>
<body>
    <h1>Nextflow Execution Report Not Generated</h1>
    <div class="info-box">
        <p>To generate an execution report for your Nextflow workflow, you can either:</p>
        <p><strong>Option 1:</strong> Enable the report in your <code>nextflow.config</code>:</p>
        <pre>report {
    enabled = true
    file = "path/to/lamin_report-\${new Date().format('yyyyMMdd-HHmmss')}.html"
}</pre>
        <p><strong>Option 2:</strong> Add the <code>-with-report path/to/report.html</code> flag to your nextflow run command:</p>
        <pre>nextflow run your_pipeline.nf -with-report path/to/report.html</pre>
        <p>For more information, see the <a href="https://www.nextflow.io/docs/latest/reports.html#execution-report" target="_blank">Nextflow documentation</a>.</p>
    </div>
</body>
</html>"""
            Files.write(reportPath, placeholderHtml.getBytes('UTF-8'))
        }

        // Create artifact from the report path
        try {
            String description = "Nextflow execution report for run ${run?.get('uid')}"

            Map<String, Object> artifact = fetchOrCreateArtifact(
                path: reportPath,
                run_id: null,
                description: description,
                kind: "__lamindb_run__"
            )

            if (artifact) {
                Integer artifactId = (artifact.get('id') as Number)?.intValue()
                String artifactUid = artifact.get('uid') as String

                // If id is missing, fetch it using the uid
                if (artifactId == null && artifactUid != null) {
                    log.debug "Artifact ID missing from API response, looking up artifact by UID: ${artifactUid}"
                    try {
                        Map<String, Object> fetchedArtifact = laminInstance.getRecord(
                            moduleName: 'core',
                            modelName: 'artifact',
                            idOrUid: artifactUid
                        )
                        artifactId = (fetchedArtifact.get('id') as Number)?.intValue()
                    } catch (Exception e) {
                        log.warn "Failed to fetch artifact ID for UID ${artifactUid}: ${e.getMessage()}"
                    }
                }

                if (isPlaceholder) {
                    log.debug "Created placeholder report artifact ${artifactUid}"
                } else {
                    log.info "Created report artifact ${artifactUid}"
                }
                return artifactId
            }
            return null
        } finally {
            // Clean up temp file if we created one
            if (tempReportPath != null) {
                try {
                    Files.delete(tempReportPath)
                } catch (Exception e) {
                    log.debug "Failed to delete temporary report file: ${e.getMessage()}"
                }
            }
        }
    }

    private void printTransformMessage(Map transformRecord, String message) {
        String webUrl = resolvedConfig != null ? resolvedConfig.webUrl : null
        String owner = laminInstance != null ? laminInstance.getOwner() : null
        String name = laminInstance != null ? laminInstance.getName() : null
        String transformUid = transformRecord.get('uid') as String
        if (webUrl && owner && name && transformUid) {
            log.info "${message} (${webUrl}/${owner}/${name}/transform/${transformUid})"
        } else {
            log.info message
        }
    }

    private void printRunMessage(Map runRecord, String message) {
        String webUrl = resolvedConfig != null ? resolvedConfig.webUrl : null
        String owner = laminInstance != null ? laminInstance.getOwner() : null
        String name = laminInstance != null ? laminInstance.getName() : null
        String transformUid = transform != null ? transform.get('uid') as String : null
        String runUid = runRecord.get('uid') as String
        if (webUrl && owner && name && transformUid && runUid) {
            log.info "${message} (${webUrl}/${owner}/${name}/transform/${transformUid}/${runUid})"
        } else {
            log.info message
        }
    }

    private void ensureInitialized(String message) {
        if (session == null || config == null || laminInstance == null) {
            throw new IllegalStateException(message)
        }
    }
}
