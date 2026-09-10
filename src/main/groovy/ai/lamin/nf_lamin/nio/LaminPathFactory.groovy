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

import java.nio.file.Path

import nextflow.file.FileHelper
import nextflow.file.FileSystemPathFactory
import org.pf4j.Extension

/**
 * FileSystemPathFactory implementation for Lamin URIs.
 *
 * This factory allows Nextflow to parse lamin:// URI strings into
 * LaminPath objects, and convert LaminPath objects back to URI strings.
 */
@Slf4j
@Extension
@CompileStatic
class LaminPathFactory extends FileSystemPathFactory {

    /**
     * Parse a URI string into a Path.
     *
     * Nextflow hands over the string as the user wrote it, so this is the one place a publish
     * target's query string is guaranteed to arrive intact: Nextflow's own fallback folds it
     * into the path.
     *
     * @param uriString The URI string (e.g., "lamin://owner/instance/artifact/uid")
     * @return A path if the URI is a lamin:// URI, null otherwise
     * @throws IllegalArgumentException if the URI is a lamin:// URI that cannot be parsed or resolved
     */
    @Override
    protected Path parseUri(String uriString) {
        // Only handle lamin:// URIs
        if (!uriString?.startsWith(LaminUriParser.SCHEME + ':')) {
            return null
        }

        LaminUriParser parsed = LaminUriParser.parse(uriString)
        LaminFileSystemProvider provider = FileHelper.getOrInstallProvider(LaminFileSystemProvider)
        return provider.getPath(parsed)
    }

    /**
     * Convert a Path to a URI string.
     *
     * A {@link LaminS3Path} is rendered as the {@code s3://} URI it stands for: the
     * {@code lamin-s3://} scheme only exists inside this plugin, and this string ends up in
     * logs and workflow output index files.
     *
     * @param path The path to convert
     * @return The URI string if the path belongs to this plugin, null otherwise
     */
    @Override
    protected String toUriString(Path path) {
        if (path instanceof LaminPath) {
            return ((LaminPath) path).toUriString()
        }
        if (path instanceof LaminS3Path) {
            return ((LaminS3Path) path).toStorageUri()
        }
        return null
    }

    /**
     * Get bash library code for lamin paths.
     *
     * @param target The target path
     * @return Bash library code, or null if not applicable
     */
    @Override
    protected String getBashLib(Path target) {
        // Lamin paths delegate to underlying storage, so no special bash lib needed
        return null
    }

    /**
     * Get the upload command for copying files to lamin paths.
     *
     * @param source The source file path
     * @param target The target lamin path
     * @return The upload command, or null if not supported
     */
    @Override
    protected String getUploadCmd(String source, Path target) {
        // Writing to lamin:// paths is not supported
        return null
    }
}
