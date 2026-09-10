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

import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Path implementation for lamin-s3:// URIs.
 *
 * Represents an object in an S3 bucket using temporary credentials from LaminHub.
 * URIs are of the form: lamin-s3://bucket-name/path/to/object.csv
 *
 * This path is always absolute (it always has a bucket and key).
 */
@Slf4j
@CompileStatic
final class LaminS3Path implements Path {

    static final String SCHEME = LaminS3FileSystemProvider.SCHEME
    static final String SEP = '/'

    private final LaminS3FileSystem fs
    private final String key  // object key, no leading slash

    LaminS3Path(LaminS3FileSystem fs, String key) {
        if (fs == null) throw new IllegalArgumentException("FileSystem cannot be null")
        this.fs = fs
        // normalize key: no leading, trailing or doubled slashes. Nextflow resolves publish
        // destinations from strings like "reports/${id}/", which would otherwise leave a "//"
        // in the object key.
        this.key = key?.replaceAll('/+', '/')?.replaceFirst('^/', '')?.replaceFirst('/$', '') ?: ''
    }

    String getBucket() { return fs.bucketName }
    String getKey() { return key }

    @Override
    FileSystem getFileSystem() {
        return fs
    }

    @Override
    boolean isAbsolute() {
        return true
    }

    @Override
    Path getRoot() {
        return new LaminS3Path(fs, '')
    }

    @Override
    Path getFileName() {
        if (!key) return null
        String[] parts = key.split(SEP)
        String name = parts[parts.length - 1]
        return new LaminS3Path(fs, name)
    }

    @Override
    Path getParent() {
        if (!key) return null
        int lastSep = key.lastIndexOf(SEP)
        if (lastSep <= 0) return getRoot()
        return new LaminS3Path(fs, key.substring(0, lastSep))
    }

    @Override
    int getNameCount() {
        if (!key) return 0
        return key.split(SEP).length
    }

    @Override
    Path getName(int index) {
        if (!key) throw new IllegalArgumentException("No name elements")
        String[] parts = key.split(SEP)
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("Index ${index} out of range [0, ${parts.length})")
        }
        return new LaminS3Path(fs, parts[index])
    }

    @Override
    Path subpath(int beginIndex, int endIndex) {
        if (!key) throw new IllegalArgumentException("No name elements")
        String[] parts = key.split(SEP)
        if (beginIndex < 0 || endIndex > parts.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException("Invalid subpath range [${beginIndex}, ${endIndex})")
        }
        return new LaminS3Path(fs, parts[beginIndex..<endIndex].join(SEP))
    }

    @Override
    boolean startsWith(Path other) {
        if (!(other instanceof LaminS3Path)) return false
        LaminS3Path o = (LaminS3Path) other
        return fs.storageRoot == o.fs.storageRoot && key.startsWith(o.key)
    }

    @Override
    boolean startsWith(String other) {
        return toString().startsWith(other)
    }

    @Override
    boolean endsWith(Path other) {
        if (!(other instanceof LaminS3Path)) return false
        LaminS3Path o = (LaminS3Path) other
        return fs.storageRoot == o.fs.storageRoot && key.endsWith(o.key)
    }

    @Override
    boolean endsWith(String other) {
        return key.endsWith(other)
    }

    @Override
    Path normalize() {
        return this
    }

    @Override
    Path resolve(Path other) {
        if (other instanceof LaminS3Path) {
            LaminS3Path o = (LaminS3Path) other
            if (o.isAbsolute()) return o
            String newKey = key ? "${key}/${o.key}" : o.key
            return new LaminS3Path(fs, newKey)
        }
        if (other instanceof Path) {
            String otherStr = other.toString()
            if (!otherStr) return this
            String newKey = key ? "${key}/${otherStr}" : otherStr
            return new LaminS3Path(fs, newKey)
        }
        throw new ProviderMismatchException("Cannot resolve ${other?.class?.name}")
    }

    @Override
    Path resolve(String other) {
        if (!other) return this
        String newKey = key ? "${key}/${other}" : other
        return new LaminS3Path(fs, newKey)
    }

    @Override
    Path resolveSibling(Path other) {
        Path parent = getParent()
        return parent != null ? parent.resolve(other) : other
    }

    @Override
    Path resolveSibling(String other) {
        Path parent = getParent()
        return parent != null ? parent.resolve(other) : new LaminS3Path(fs, other)
    }

    @Override
    Path relativize(Path other) {
        if (!(other instanceof LaminS3Path)) throw new ProviderMismatchException()
        LaminS3Path o = (LaminS3Path) other
        if (fs.storageRoot != o.fs.storageRoot) throw new IllegalArgumentException("Cannot relativize across different storage roots")
        if (!o.key.startsWith(key)) throw new IllegalArgumentException("Cannot relativize ${o} against ${this}")
        String relative = o.key.substring(key.length()).replaceFirst('^/', '')
        return new LaminS3Path(fs, relative)
    }

    @Override
    URI toUri() {
        return new URI(SCHEME, bucket, "/${key}", null, null)
    }

    /**
     * The same object addressed with the standard {@code s3://} scheme.
     *
     * The {@code lamin-s3://} scheme exists only to attach Lamin-managed credentials to a
     * bucket, so anything outside this plugin -- the Lamin API in particular -- expects the
     * underlying {@code s3://} form.
     *
     * @return the storage URI, e.g. {@code s3://my-bucket/prefix/file.h5ad}
     */
    String toStorageUri() {
        return "s3://${bucket}/${key}"
    }

    @Override
    Path toAbsolutePath() {
        return this
    }

    @Override
    Path toRealPath(LinkOption... options) throws IOException {
        return this
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException("WatchService not supported for lamin-s3:// paths")
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new UnsupportedOperationException("WatchService not supported for lamin-s3:// paths")
    }

    @Override
    Iterator<Path> iterator() {
        List<Path> names = []
        if (key) {
            key.split(SEP).each { String part ->
                names << new LaminS3Path(fs, part)
            }
        }
        return names.iterator()
    }

    @Override
    int compareTo(Path other) {
        return toString().compareTo(other.toString())
    }

    @Override
    boolean equals(Object obj) {
        if (!(obj instanceof LaminS3Path)) return false
        LaminS3Path o = (LaminS3Path) obj
        return bucket == o.bucket && key == o.key
    }

    @Override
    int hashCode() {
        return Objects.hash(fs.storageRoot, key)
    }

    @Override
    String toString() {
        return "${SCHEME}://${bucket}/${key}"
    }
}
