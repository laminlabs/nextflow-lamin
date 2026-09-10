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

import java.nio.file.Path
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.file.FileHolder
import nextflow.processor.TaskRun
import nextflow.trace.TraceObserverV2
import nextflow.trace.event.TaskEvent
import nextflow.trace.event.FilePublishEvent
import nextflow.trace.event.WorkflowOutputEvent

import ai.lamin.nf_lamin.model.RunStatus
import ai.lamin.nf_lamin.nio.LaminS3Path

/**
 * Implements workflow events observer for Lamin provenance tracking
 *
 * This observer tracks workflow execution events and creates corresponding
 * records in Lamin for provenance and metadata management.
 */
@Slf4j
@CompileStatic
class LaminObserver implements TraceObserverV2 {

    private final LaminRunManager state = LaminRunManager.instance
    private volatile boolean runFinalized = false
    private volatile boolean trackingEnabled = false

    /**
     * Called once when the Nextflow session is created, before any process runs.
     *
     * Initialises the {@link LaminRunManager} with the session (which provides workflow
     * metadata, parameters, config, and the output directory) and pre-creates the
     * Transform and Run records in LaminDB so that a Run UID is available for the
     * rest of the workflow.
     */
    @Override
    void onFlowCreate(Session session) {
        log.debug "LaminObserver.onFlowCreate"

        // Run tracking is optional. When no instance is configured, the plugin is present
        // only to resolve lamin:// URIs, so skip run tracking instead of failing the run.
        if (!LaminConfig.isTrackingConfigured(session)) {
            log.debug "nf-lamin: no instance configured; run tracking disabled (lamin:// URIs can still be resolved)"
            if (session?.outputDir instanceof LaminS3Path) {
                log.warn "Publishing to ${session.outputDir}, but no Lamin instance is configured: files will be " +
                    "written to storage without being registered as artifacts. Set lamin.instance to register them."
            }
            trackingEnabled = false
            runFinalized = true
            return
        }

        try {
            state.initializeRunManager(session)
            state.initializeRun()
            trackingEnabled = true
            runFinalized = false
        } catch (Exception e) {
            log.error "Could not initialize Lamin run: ${e.message}"
            throw e
        }
    }

    /**
     * Called when the workflow execution starts (after processes and channels are set up).
     *
     * Marks the Run as started in LaminDB and processes any explicitly configured input
     * artifact paths (from {@code lamin.artifacts} or {@code lamin.input_artifacts} config),
     * creating input Artifact records and linking them to the Run.
     */
    @Override
    void onFlowBegin() {
        log.debug "LaminObserver.onFlowBegin"
        if (!trackingEnabled) return
        state.startRun()
        state.processConfigPathsAsync('input')
    }

    /**
     * Called each time a file is published to a {@code publishDir} destination.
     *
     * {@code event.source} is the path the file was published from - the same path the workflow
     * holds in its channels, and therefore the one {@code annotateArtifact()} keys on.
     * {@code event.target} is the final published path (local or cloud). {@code event.labels}
     * are the strings declared with the {@code label} directive on the output's publish block.
     *
     * <p>Two cases are handled:</p>
     * <ul>
     *   <li><strong>Normal content file</strong>: creates an output Artifact for the target
     *       path and links any publishDir labels as ULabels (if the
     *       {@code features.publish_dir_labels} flag is enabled).</li>
     *   <li><strong>Index/manifest file</strong> (written after {@code onWorkflowOutput}):
     *       creates the Artifact, using the output name {@link #onWorkflowOutput} recorded
     *       for it.</li>
     * </ul>
     */
    @Override
    void onFilePublish(FilePublishEvent event) {
        log.debug "LaminObserver.onFilePublish: ${event.source} -> ${event.target}"
        if (!trackingEnabled) return
        state.createOutputArtifactOnFilePublishAsync(event.source, event.target, event.labels)
    }

    /**
     * Called once per named workflow output block after all its channel values have been published.
     *
     * {@code event.name} is the output block name (e.g. {@code 'bam_files'}). {@code event.value}
     * is the fully published value: a single Path, a list of Paths, or a structured map.
     * {@code event.index} is the optional manifest file path (CSV/JSON/YAML) that Nextflow
     * writes to summarise all published records; it is non-null only when the output block
     * contains an {@code index} directive.
     *
     * <p>For content files ({@code event.value}) this hook fires <em>after</em> the individual
     * {@link #onFilePublish} calls, so the artifacts already exist in the cache and
     * {@link LaminRunManager#createOutputArtifactOnWorkflowOutput} is a no-op for those paths.</p>
     *
     * <p>For index/manifest files ({@code event.index}) this hook fires <em>before</em> the
     * file is written, and on a re-run the file is deleted and rewritten in between, so only
     * the output name is recorded here; the subsequent {@link #onFilePublish} creates the
     * artifact.</p>
     */
    @Override
    void onWorkflowOutput(WorkflowOutputEvent event) {
        log.debug "LaminObserver.onWorkflowOutput: ${event.name} = ${event.value}"
        if (!trackingEnabled) return
        if (event.value instanceof Path) {
            state.createOutputArtifactOnWorkflowOutputAsync((Path) event.value, event.name)
        } else if (event.value instanceof Collection) {
            for (Object item : (Collection) event.value) {
                if (item instanceof Path) {
                    state.createOutputArtifactOnWorkflowOutputAsync((Path) item, event.name)
                }
            }
        }
        if (event.index != null) {
            state.rememberOutputName(event.index, event.name)
        }
    }

    /**
     * Called each time a process task finishes successfully.
     *
     * Inspects the task's staged input files and creates an input Artifact record for each
     * source path (the original file before staging). This captures which files were consumed
     * as inputs, linking them to the Run for lineage tracking. Paths inside the Nextflow work
     * directory and other excluded locations are filtered out by {@link LaminRunManager}.
     */
    @Override
    void onTaskComplete(TaskEvent event) {
        if (!trackingEnabled) return
        TaskRun task = event.handler.task
        List<FileHolder> inputFiles = task.getInputFiles()

        log.debug "LaminObserver.onTaskComplete: ${task.name} with inputFiles: ${inputFiles}"

        List<Path> sources = inputFiles.collect { it.getSourcePath() }
        state.createInputArtifactsAsync(task.name, sources)
    }

    /**
     * Called when the workflow finishes successfully.
     *
     * Delegates to {@link #finalizeRunOnce()} which processes any explicitly configured output
     * artifact paths and then updates the Run record in LaminDB with the finish time and
     * {@code COMPLETED} status.
     */
    @Override
    void onFlowComplete() {
        log.debug "LaminObserver.onFlowComplete"
        finalizeRunOnce()
    }

    /**
     * Called when the workflow terminates due to a task failure or other error.
     *
     * Delegates to {@link #finalizeRunOnce()} which updates the Run record in LaminDB with
     * an {@code ERRORED} or {@code ABORTED} status. The guard in {@code finalizeRunOnce}
     * ensures that if both {@code onFlowError} and {@code onFlowComplete} fire (which can
     * happen on cancellation), the Run is only finalised once.
     */
    @Override
    void onFlowError(TaskEvent event) {
        log.debug "LaminObserver.onFlowError"
        finalizeRunOnce()
    }

    private synchronized void finalizeRunOnce() {
        if (runFinalized) {
            log.debug "Run already finalized, skipping duplicate finalization"
            return
        }
        runFinalized = true
        state.processConfigPathsAsync('output')
        state.awaitArtifactTasks()
        state.warnUnmatchedAnnotations()
        state.finalizeRun()
    }
}
