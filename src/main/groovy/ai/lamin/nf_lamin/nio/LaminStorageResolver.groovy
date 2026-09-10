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

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import ai.lamin.nf_lamin.hub.StorageSettings
import ai.lamin.nf_lamin.instance.Instance

/**
 * Resolves the space and storage selectors of a {@code lamin://} publish target to the storage
 * location to write to.
 *
 * Follows the same rules as {@code ln.Artifact(space=..., storage=...)}: a storage decides its
 * space, a space without storage gets the instance's lowest-id storage location in that space,
 * and the two must agree. Results are cached, because Nextflow parses the target once per task.
 */
@Slf4j
@CompileStatic
class LaminStorageResolver {

    /** The built-in "all" space every record belongs to unless placed elsewhere. */
    private static final int DEFAULT_SPACE_ID = 1

    private final Map<String, LaminStorageTarget> cache = new ConcurrentHashMap<String, LaminStorageTarget>()

    /**
     * Resolve a publish target to its storage location.
     *
     * @param instance The client for the target's instance
     * @param uri The parsed target; must be a storage URI
     * @return The storage location to publish into
     * @throws IllegalArgumentException if a selector does not exist, the selectors disagree, or
     *         the storage is not managed by this instance
     */
    LaminStorageTarget resolve(Instance instance, LaminUriParser uri) {
        if (!uri.isStorage()) {
            throw new IllegalArgumentException("Not a storage URI: ${uri}")
        }
        String cacheKey = "${uri.instanceSlug}|${uri.spaceUid}|${uri.storageUid}"
        return cache.computeIfAbsent(cacheKey) { String key ->
            LaminStorageTarget target = resolve0(instance, uri)
            log.debug "Resolved ${uri} to ${target}"
            return target
        }
    }

    /**
     * Drop all cached targets.
     */
    void clear() {
        cache.clear()
    }

    private LaminStorageTarget resolve0(Instance instance, LaminUriParser uri) {
        String slug = uri.instanceSlug
        String instanceUid = instance.settings.lnid

        // space, when selected
        Map space = null
        Integer spaceId = null
        if (uri.spaceUid) {
            space = instance.getRecord(moduleName: 'core', modelName: 'space', idOrUid: uri.spaceUid)
            if (space == null) {
                throw new IllegalArgumentException("No space with uid '${uri.spaceUid}' in instance '${slug}'")
            }
            spaceId = intOf(space.get('id'))
        }

        // storage, when selected or implied by the space
        Map storage = null
        if (uri.storageUid) {
            storage = instance.getRecord(moduleName: 'core', modelName: 'storage', idOrUid: uri.storageUid)
            if (storage == null) {
                throw new IllegalArgumentException("No storage with uid '${uri.storageUid}' in instance '${slug}'")
            }
        }
        else if (spaceId != null) {
            List<Map> candidates = instance.getRecords(
                moduleName: 'core',
                modelName: 'storage',
                limit: 1,
                filter: [and: [[space_id: [eq: spaceId]], [instance_uid: [eq: instanceUid]]]],
                orderBy: [[field: 'id', descending: false]]
            )
            if (!candidates) {
                throw new IllegalArgumentException(
                    "No storage location found for space '${uri.spaceUid}' in instance '${slug}'. " +
                    "Create one with ln.Storage(root='...', space=space).save(), or select one with '?storage=<uid>'"
                )
            }
            storage = candidates[0]
        }

        // neither selected: the instance's default storage
        if (storage == null) {
            StorageSettings defaults = instance.settings.storage
            if (!defaults?.root) {
                throw new IllegalArgumentException(
                    "Instance '${slug}' has no default storage location; select one with '?storage=<uid>'"
                )
            }
            return new LaminStorageTarget(
                storageRoot: defaults.root,
                storageUid: defaults.lnid,
                type: defaults.type,
                region: defaults.region
            )
        }

        String storageUid = storage.get('uid') as String
        Integer storageSpaceId = intOf(storage.get('space_id'))
        if (spaceId != null && storageSpaceId != null && storageSpaceId != spaceId) {
            throw new IllegalArgumentException(
                "Storage '${storageUid}' belongs to space ${storageSpaceId}, but the target selects space " +
                "'${uri.spaceUid}' (${spaceId}). LaminDB requires them to match."
            )
        }

        String managingInstance = storage.get('instance_uid') as String
        if (managingInstance && instanceUid && managingInstance != instanceUid) {
            throw new IllegalArgumentException(
                "Storage '${storageUid}' (${storage.get('root')}) is read-only in instance '${slug}': it is " +
                "managed by instance '${managingInstance}'"
            )
        }

        // a storage in the default "all" space leaves the space to the plugin config
        Integer targetSpaceId = spaceId ?: (storageSpaceId != null && storageSpaceId != DEFAULT_SPACE_ID ? storageSpaceId : null)

        return new LaminStorageTarget(
            storageRoot: storage.get('root') as String,
            storageUid: storageUid,
            storageId: intOf(storage.get('id')),
            spaceId: targetSpaceId,
            spaceUid: space?.get('uid') as String,
            type: storage.get('type') as String,
            region: storage.get('region') as String
        )
    }

    private static Integer intOf(Object value) {
        return value == null ? null : ((Number) value).intValue()
    }
}
