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

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Parser for Lamin URIs.
 *
 * Two forms are accepted:
 *
 * <pre>
 * lamin://owner/instance/artifact/uid[/subpath]                      (an artifact, read-only)
 * lamin://owner/instance?space=&lt;uid&gt;&amp;storage=&lt;uid&gt;&amp;prefix=&lt;key&gt;   (a storage location, to publish into)
 * </pre>
 *
 * Examples:
 * - lamin://laminlabs/lamindata/artifact/s3rtK8wIzJNKvg5Q
 * - lamin://laminlabs/lamindata/artifact/s3rtK8wIzJNKvg5Q/subdir/file.txt
 * - lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&amp;prefix=results
 */
@Slf4j
@CompileStatic
class LaminUriParser {

    static final String SCHEME = 'lamin'
    static final String SEP = '/'

    static final String PARAM_SPACE = 'space'
    static final String PARAM_STORAGE = 'storage'
    static final String PARAM_PREFIX = 'prefix'
    private static final List<String> KNOWN_PARAMS = [PARAM_SPACE, PARAM_STORAGE, PARAM_PREFIX]

    /** Key prefix LaminDB reserves for artifacts it manages itself. */
    static final String RESERVED_PREFIX = '.lamindb'

    private static final String GRAMMAR =
        "lamin://owner/instance/artifact/uid[/subpath] or lamin://owner/instance?space=<uid>&storage=<uid>&prefix=<key>"

    /**
     * Whether this URI points at an artifact or at a storage location
     */
    final LaminUriKind kind

    /**
     * The owner of the LaminDB instance (e.g., "laminlabs")
     */
    final String owner

    /**
     * The name of the LaminDB instance (e.g., "lamindata")
     */
    final String instance

    /**
     * The resource type ("artifact"); null for storage URIs
     */
    final String resourceType

    /**
     * The resource identifier (e.g., artifact UID "s3rtK8wIzJNKvg5Q"); null for storage URIs
     */
    final String resourceId

    /**
     * Optional sub-path within the artifact (for directories); null for storage URIs
     */
    final String subPath

    /**
     * UID of the space to publish into; null when not given or for artifact URIs
     */
    final String spaceUid

    /**
     * UID of the storage location to publish into; null when not given or for artifact URIs
     */
    final String storageUid

    /**
     * Key prefix within the storage location, without leading or trailing slashes; null when not given
     */
    final String prefix

    /**
     * Private constructor - use parse() factory methods instead.
     */
    private LaminUriParser(String owner, String instance, String resourceType, String resourceId, String subPath) {
        this.kind = LaminUriKind.ARTIFACT
        this.owner = owner
        this.instance = instance
        this.resourceType = resourceType
        this.resourceId = resourceId
        this.subPath = subPath
        this.spaceUid = null
        this.storageUid = null
        this.prefix = null
    }

    private LaminUriParser(String owner, String instance, String spaceUid, String storageUid, String prefix, LaminUriKind kind) {
        this.kind = kind
        this.owner = owner
        this.instance = instance
        this.resourceType = null
        this.resourceId = null
        this.subPath = null
        this.spaceUid = spaceUid
        this.storageUid = storageUid
        this.prefix = prefix
    }

    /**
     * Parse a URI string into a LaminUriParser.
     *
     * The string is parsed by hand rather than through {@link URI}, because a key prefix may
     * contain characters (spaces, braces) that {@link URI} rejects.
     *
     * @param uriString The URI string to parse (e.g., "lamin://laminlabs/lamindata/artifact/uid")
     * @return A LaminUriParser instance
     * @throws IllegalArgumentException if the URI is invalid
     */
    static LaminUriParser parse(String uriString) {
        if (!uriString?.trim()) {
            throw new IllegalArgumentException("URI string cannot be null or empty")
        }
        String str = uriString.trim()

        // scheme
        int colon = str.indexOf(':')
        String scheme = colon > 0 ? str.substring(0, colon).toLowerCase() : null
        if (scheme != SCHEME) {
            throw new IllegalArgumentException("Invalid scheme '${scheme}'. Expected '${SCHEME}'")
        }
        String rest = str.substring(colon + 1)
        if (rest.startsWith('//')) {
            rest = rest.substring(2)
        }

        // split off the query
        String query = null
        int qmark = rest.indexOf('?')
        if (qmark >= 0) {
            query = rest.substring(qmark + 1)
            rest = rest.substring(0, qmark)
        }

        return parse0(rest, query, str)
    }

    /**
     * Parse a URI into a LaminUriParser.
     *
     * @param uri The URI to parse
     * @return A LaminUriParser instance
     * @throws IllegalArgumentException if the URI is invalid
     */
    static LaminUriParser parse(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null")
        }
        return parse(uri.toString())
    }

    private static LaminUriParser parse0(String path, String query, String original) {
        if (!path?.trim()) {
            throw new IllegalArgumentException("URI path cannot be empty")
        }

        String[] parts = path.split(SEP)
        if (parts.length < 2 || !parts[0]?.trim() || !parts[1]?.trim()) {
            throw new IllegalArgumentException("Invalid URI format. Expected: ${GRAMMAR}. Got: ${original}")
        }
        String owner = parts[0]
        String instance = parts[1]

        // storage form: nothing after owner/instance
        if (parts.length == 2) {
            return parseStorage(owner, instance, query, original)
        }

        // artifact form: owner/instance/artifact/uid[/subpath]
        if (query != null) {
            throw new IllegalArgumentException("Query parameters are not supported on artifact URIs: ${original}")
        }
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid URI format. Expected: ${GRAMMAR}. Got: ${original}")
        }

        String resourceType = parts[2]
        String resourceId = parts[3]
        if (!resourceType?.trim()) {
            throw new IllegalArgumentException("Resource type cannot be empty in URI: ${original}")
        }
        if (!resourceId?.trim()) {
            throw new IllegalArgumentException("Resource ID cannot be empty in URI: ${original}")
        }
        if (resourceType != 'artifact') {
            throw new IllegalArgumentException(
                "Unsupported resource type '${resourceType}'. Currently only 'artifact' is supported."
            )
        }

        String subPath = null
        if (parts.length > 4) {
            subPath = parts[4..-1].join(SEP)
        }

        log.trace "Parsed URI: owner=${owner}, instance=${instance}, resourceType=${resourceType}, resourceId=${resourceId}, subPath=${subPath}"

        return new LaminUriParser(owner, instance, resourceType, resourceId, subPath)
    }

    private static LaminUriParser parseStorage(String owner, String instance, String query, String original) {
        Map<String, String> params = parseQuery(query, original)

        String spaceUid = requireNonEmpty(params, PARAM_SPACE, original)
        String storageUid = requireNonEmpty(params, PARAM_STORAGE, original)
        String prefix = normalisePrefix(params.get(PARAM_PREFIX), original)

        log.trace "Parsed URI: owner=${owner}, instance=${instance}, space=${spaceUid}, storage=${storageUid}, prefix=${prefix}"

        return new LaminUriParser(owner, instance, spaceUid, storageUid, prefix, LaminUriKind.STORAGE)
    }

    private static Map<String, String> parseQuery(String query, String original) {
        Map<String, String> params = [:]
        if (!query?.trim()) {
            return params
        }
        for (String pair : query.split('&')) {
            if (!pair) {
                continue
            }
            int eq = pair.indexOf('=')
            String key = decode(eq >= 0 ? pair.substring(0, eq) : pair)
            String value = eq >= 0 ? decode(pair.substring(eq + 1)) : ''
            if (!KNOWN_PARAMS.contains(key)) {
                throw new IllegalArgumentException(
                    "Unknown query parameter '${key}' in URI: ${original}. Supported: ${KNOWN_PARAMS.join(', ')}"
                )
            }
            if (params.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate query parameter '${key}' in URI: ${original}")
            }
            params.put(key, value)
        }
        return params
    }

    private static String requireNonEmpty(Map<String, String> params, String key, String original) {
        if (!params.containsKey(key)) {
            return null
        }
        String value = params.get(key)?.trim()
        if (!value) {
            throw new IllegalArgumentException("Query parameter '${key}' cannot be empty in URI: ${original}")
        }
        return value
    }

    /**
     * Strip leading, trailing and doubled slashes from a key prefix, and refuse prefixes that
     * escape the storage root or land in the part of it LaminDB manages itself.
     */
    private static String normalisePrefix(String raw, String original) {
        if (raw == null) {
            return null
        }
        List<String> segments = raw.split(SEP).findAll { String s -> s.length() > 0 } as List<String>
        if (segments.isEmpty()) {
            return null
        }
        if (segments.contains('..')) {
            throw new IllegalArgumentException("Prefix cannot contain '..' in URI: ${original}")
        }
        if (segments[0] == RESERVED_PREFIX) {
            throw new IllegalArgumentException(
                "Prefix '${RESERVED_PREFIX}/' is reserved by LaminDB and cannot be published to: ${original}"
            )
        }
        return segments.join(SEP)
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private static String encode(String value) {
        // URLEncoder is for forms: it turns spaces into '+' and escapes '/'
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace('+', '%20')
            .replace('%2F', SEP)
    }

    /**
     * Whether this URI points at an artifact
     */
    boolean isArtifact() {
        return kind == LaminUriKind.ARTIFACT
    }

    /**
     * Whether this URI points at a storage location
     */
    boolean isStorage() {
        return kind == LaminUriKind.STORAGE
    }

    /**
     * Get the instance slug in format "owner/instance"
     */
    String getInstanceSlug() {
        return "${owner}/${instance}"
    }

    /**
     * Check if this URI has a sub-path
     */
    boolean hasSubPath() {
        return subPath != null && !subPath.isEmpty()
    }

    /**
     * Convert back to a URI string.
     *
     * Storage URIs are rendered in one canonical form (space, storage, prefix, in that order, with
     * the prefix percent-encoded), so that {@code parse(x.toUriString()) == x}.
     */
    String toUriString() {
        StringBuilder sb = new StringBuilder()
        sb.append(SCHEME).append('://').append(owner).append(SEP).append(instance)
        if (isStorage()) {
            List<String> params = []
            if (spaceUid) params.add("${PARAM_SPACE}=${spaceUid}".toString())
            if (storageUid) params.add("${PARAM_STORAGE}=${storageUid}".toString())
            if (prefix) params.add("${PARAM_PREFIX}=${encode(prefix)}".toString())
            if (params) {
                sb.append('?').append(params.join('&'))
            }
            return sb.toString()
        }
        sb.append(SEP).append(resourceType).append(SEP).append(resourceId)
        if (hasSubPath()) {
            sb.append(SEP).append(subPath)
        }
        return sb.toString()
    }

    /**
     * Convert to a URI object
     */
    URI toUri() {
        return new URI(toUriString())
    }

    /**
     * Create a new LaminUriParser with an appended sub-path
     */
    LaminUriParser withSubPath(String additionalPath) {
        if (!additionalPath?.trim()) {
            return this
        }
        String newSubPath = hasSubPath() ? "${subPath}/${additionalPath}" : additionalPath
        return new LaminUriParser(owner, instance, resourceType, resourceId, newSubPath)
    }

    /**
     * Create a new LaminUriParser with the sub-path removed
     */
    LaminUriParser withoutSubPath() {
        return new LaminUriParser(owner, instance, resourceType, resourceId, null)
    }

    /**
     * Get the last component of the sub-path (filename)
     */
    String getFileName() {
        if (!hasSubPath()) {
            return resourceId
        }
        int lastSep = subPath.lastIndexOf(SEP)
        return lastSep >= 0 ? subPath.substring(lastSep + 1) : subPath
    }

    /**
     * Get the parent path (sub-path without the last component)
     */
    LaminUriParser getParent() {
        if (!hasSubPath()) {
            return null
        }
        int lastSep = subPath.lastIndexOf(SEP)
        if (lastSep <= 0) {
            return new LaminUriParser(owner, instance, resourceType, resourceId, null)
        }
        return new LaminUriParser(owner, instance, resourceType, resourceId, subPath.substring(0, lastSep))
    }

    @Override
    String toString() {
        return toUriString()
    }

    @Override
    boolean equals(Object obj) {
        if (this.is(obj)) return true
        if (!(obj instanceof LaminUriParser)) return false
        LaminUriParser other = (LaminUriParser) obj
        return kind == other.kind &&
               owner == other.owner &&
               instance == other.instance &&
               resourceType == other.resourceType &&
               resourceId == other.resourceId &&
               subPath == other.subPath &&
               spaceUid == other.spaceUid &&
               storageUid == other.storageUid &&
               prefix == other.prefix
    }

    @Override
    int hashCode() {
        return Objects.hash(kind, owner, instance, resourceType, resourceId, subPath, spaceUid, storageUid, prefix)
    }
}
