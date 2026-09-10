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

/**
 * What a {@code lamin://} URI points at.
 */
@CompileStatic
enum LaminUriKind {
    /** {@code lamin://owner/instance/artifact/<uid>[/subpath]}: an existing artifact, read-only. */
    ARTIFACT,
    /** {@code lamin://owner/instance?space=&storage=&prefix=}: a location in a storage location, to publish into. */
    STORAGE
}
