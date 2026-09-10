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
import groovy.transform.Immutable

/**
 * The storage location a {@code lamin://} publish target resolved to.
 */
@Immutable
@CompileStatic
class LaminStorageTarget {

    /** Storage root URI, e.g. {@code s3://lamin-eu/JwMEKs04D9WJ} */
    String storageRoot

    /** UID of the storage location */
    String storageUid

    /** Integer id of the storage record; null when only the hub's default storage settings were available */
    Integer storageId

    /** Space the published artifacts belong to; null when left to the plugin config */
    Integer spaceId

    /** UID of that space, when it was selected explicitly */
    String spaceUid

    /** Storage backend, e.g. {@code s3} */
    String type

    /** Region of the bucket, when known */
    String region

    /**
     * The key of a path under this storage root, i.e. the root's own key prefix followed by
     * the given prefix.
     */
    String keyFor(String prefix) {
        String rootKey = new URI(storageRoot).path?.replaceFirst('^/', '')?.replaceFirst('/$', '') ?: ''
        return [rootKey, prefix].findAll { String s -> s }.join('/')
    }
}
