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

import java.nio.file.ProviderMismatchException
import java.nio.file.WatchService

import software.amazon.awssdk.services.s3.S3Client as AwsS3Client

class LaminS3PathTest extends Specification {

    LaminS3FileSystemProvider provider
    AwsS3Client s3Client
    LaminS3FileSystem fs
    LaminS3FileSystem fs2  // different storageRoot, same bucket

    def setup() {
        provider = Mock(LaminS3FileSystemProvider)
        s3Client = Mock(AwsS3Client)
        fs  = new LaminS3FileSystem(provider, 's3://my-bucket/prefix', s3Client)
        fs2 = new LaminS3FileSystem(provider, 's3://my-bucket/other',  s3Client)
    }

    private LaminS3Path path(String key) {
        return new LaminS3Path(fs, key)
    }

    // ==================== Constructor ====================

    def "constructor collapses duplicate slashes and strips a trailing slash"() {
        expect:
        path('a//b/').key == 'a/b'
        path('/a///b//c').key == 'a/b/c'
        path('/').key == ''
    }

    def "resolve() against a key with a trailing slash does not double the separator"() {
        expect:
        path('reports/sample_1/').resolve('report.json').key == 'reports/sample_1/report.json'
        path('reports/sample_1/').resolve('tables/').resolve('counts.csv').key == 'reports/sample_1/tables/counts.csv'
    }

    def "constructor should throw on null filesystem"() {
        when:
        new LaminS3Path(null, 'some/key')

        then:
        thrown(IllegalArgumentException)
    }

    def "constructor should strip leading slash from key"() {
        expect:
        path('/prefix/file.txt').key == 'prefix/file.txt'
    }

    def "constructor should handle empty key"() {
        expect:
        path('').key == ''
    }

    def "constructor should handle null key"() {
        expect:
        new LaminS3Path(fs, null).key == ''
    }

    // ==================== Properties ====================

    def "getBucket() delegates to fs.bucketName"() {
        expect:
        path('k').bucket == 'my-bucket'
    }

    def "getKey() returns the object key"() {
        expect:
        path('dir/file.txt').key == 'dir/file.txt'
    }

    def "getFileSystem() returns the fs"() {
        expect:
        path('k').fileSystem == fs
    }

    // ==================== isAbsolute ====================

    def "isAbsolute() always returns true"() {
        expect:
        path('any/key').isAbsolute()
        path('').isAbsolute()
    }

    // ==================== getRoot ====================

    def "getRoot() returns a path with an empty key"() {
        when:
        def root = path('a/b/c').root

        then:
        root instanceof LaminS3Path
        (root as LaminS3Path).key == ''
    }

    // ==================== getFileName ====================

    def "getFileName() returns the last path segment"() {
        expect:
        (path('dir/sub/file.txt').fileName as LaminS3Path).key == 'file.txt'
    }

    def "getFileName() returns null for empty key"() {
        expect:
        path('').fileName == null
    }

    def "getFileName() returns single-segment key"() {
        expect:
        (path('file.txt').fileName as LaminS3Path).key == 'file.txt'
    }

    // ==================== getParent ====================

    def "getParent() returns null for empty key"() {
        expect:
        path('').parent == null
    }

    def "getParent() returns root for a single-segment key"() {
        when:
        def parent = path('file.txt').parent

        then:
        parent instanceof LaminS3Path
        (parent as LaminS3Path).key == ''
    }

    def "getParent() returns the parent directory"() {
        when:
        def parent = path('a/b/c.txt').parent

        then:
        parent instanceof LaminS3Path
        (parent as LaminS3Path).key == 'a/b'
    }

    // ==================== getNameCount ====================

    def "getNameCount() returns 0 for empty key"() {
        expect:
        path('').nameCount == 0
    }

    def "getNameCount() returns segment count"() {
        expect:
        path('a').nameCount == 1
        path('a/b').nameCount == 2
        path('a/b/c').nameCount == 3
    }

    // ==================== getName ====================

    def "getName() returns the correct segment at each index"() {
        given:
        def p = path('a/b/c')

        expect:
        (p.getName(0) as LaminS3Path).key == 'a'
        (p.getName(1) as LaminS3Path).key == 'b'
        (p.getName(2) as LaminS3Path).key == 'c'
    }

    def "getName() out of range throws IllegalArgumentException"() {
        when:
        path('a/b').getName(5)

        then:
        thrown(IllegalArgumentException)
    }

    def "getName() negative index throws IllegalArgumentException"() {
        when:
        path('a/b').getName(-1)

        then:
        thrown(IllegalArgumentException)
    }

    // ==================== subpath ====================

    def "subpath() returns the correct sub-range"() {
        when:
        def sub = path('a/b/c/d').subpath(1, 3)

        then:
        (sub as LaminS3Path).key == 'b/c'
    }

    def "subpath() with single-element range"() {
        when:
        def sub = path('a/b/c').subpath(1, 2)

        then:
        (sub as LaminS3Path).key == 'b'
    }

    def "subpath() with inverted range throws IllegalArgumentException"() {
        when:
        path('a/b/c').subpath(2, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "subpath() with out-of-bounds end throws IllegalArgumentException"() {
        when:
        path('a/b').subpath(0, 5)

        then:
        thrown(IllegalArgumentException)
    }

    // ==================== startsWith ====================

    def "startsWith(Path) returns true for matching prefix on same storageRoot"() {
        expect:
        path('a/b/c').startsWith(path('a/b'))
        path('a/b/c').startsWith(path('a'))
    }

    def "startsWith(Path) returns true for same path"() {
        expect:
        path('a/b').startsWith(path('a/b'))
    }

    def "startsWith(Path) returns false for different storageRoot"() {
        given:
        def other = new LaminS3Path(fs2, 'a/b')

        expect:
        !path('a/b/c').startsWith(other)
    }

    def "startsWith(Path) returns false for non-LaminS3Path"() {
        expect:
        !path('a/b').startsWith(java.nio.file.Paths.get('/tmp/foo'))
    }

    def "startsWith(String) delegates to URI string representation"() {
        expect:
        path('a/b/c').startsWith('lamin-s3://my-bucket/a')
    }

    // ==================== endsWith ====================

    def "endsWith(Path) returns true for matching suffix on same storageRoot"() {
        expect:
        path('a/b/c').endsWith(path('b/c'))
        path('a/b/c').endsWith(path('c'))
    }

    def "endsWith(Path) returns false for different storageRoot"() {
        given:
        def other = new LaminS3Path(fs2, 'b/c')

        expect:
        !path('a/b/c').endsWith(other)
    }

    def "endsWith(String) checks the key suffix"() {
        expect:
        path('a/b/c.txt').endsWith('c.txt')
        path('a/b/c.txt').endsWith('b/c.txt')
    }

    // ==================== normalize ====================

    def "normalize() returns self"() {
        given:
        def p = path('a/b/c')

        expect:
        p.normalize().is(p)
    }

    // ==================== resolve ====================

    def "resolve(LaminS3Path) returns other path since LaminS3Path is always absolute"() {
        // Per Java NIO contract: if other.isAbsolute() is true, resolve(other) returns other.
        // LaminS3Path.isAbsolute() always returns true, so resolve(LaminS3Path) always returns the argument.
        when:
        def result = path('a/b').resolve(path('c/d'))

        then:
        (result as LaminS3Path).key == 'c/d'
    }

    def "resolve(String) appends the given string"() {
        when:
        def result = path('a/b').resolve('c.txt')

        then:
        (result as LaminS3Path).key == 'a/b/c.txt'
    }

    def "resolve(String) with empty string returns self"() {
        given:
        def p = path('a/b')

        expect:
        p.resolve('').is(p)
    }

    def "resolve(LaminS3Path) with empty base prepends nothing"() {
        when:
        def result = path('').resolve(path('c/d'))

        then:
        (result as LaminS3Path).key == 'c/d'
    }

    // ==================== resolveSibling ====================

    def "resolveSibling(String) replaces the last segment"() {
        when:
        def result = path('a/b/c').resolveSibling('x.txt')

        then:
        (result as LaminS3Path).key == 'a/b/x.txt'
    }

    def "resolveSibling(Path) returns the given LaminS3Path since it is always absolute"() {
        // parent.resolve(laminPath) returns laminPath because LaminS3Path.isAbsolute() is always true.
        // Use resolveSibling(String) to get sibling-replacement behaviour.
        when:
        def result = path('a/b/c').resolveSibling(path('x.txt'))

        then:
        (result as LaminS3Path).key == 'x.txt'
    }

    def "resolveSibling(String) on root path returns new path"() {
        when:
        def result = path('').resolveSibling('x.txt')

        then:
        (result as LaminS3Path).key == 'x.txt'
    }

    // ==================== relativize ====================

    def "relativize() returns the relative path between two paths"() {
        when:
        def result = path('a/b').relativize(path('a/b/c/d'))

        then:
        (result as LaminS3Path).key == 'c/d'
    }

    def "relativize() with same path returns empty key"() {
        when:
        def result = path('a/b').relativize(path('a/b'))

        then:
        (result as LaminS3Path).key == ''
    }

    def "relativize() with different storageRoot throws IllegalArgumentException"() {
        given:
        def other = new LaminS3Path(fs2, 'a/b/c')

        when:
        path('a/b').relativize(other)

        then:
        thrown(IllegalArgumentException)
    }

    def "relativize() when other does not start with this throws IllegalArgumentException"() {
        when:
        path('x/y').relativize(path('a/b'))

        then:
        thrown(IllegalArgumentException)
    }

    def "relativize() with non-LaminS3Path throws ProviderMismatchException"() {
        when:
        path('a/b').relativize(java.nio.file.Paths.get('/tmp'))

        then:
        thrown(ProviderMismatchException)
    }

    // ==================== toUri ====================

    def "toUri() produces a correct lamin-s3:// URI"() {
        when:
        def uri = path('prefix/file.txt').toUri()

        then:
        uri.scheme == 'lamin-s3'
        uri.host == 'my-bucket'
        uri.path == '/prefix/file.txt'
    }

    def "toUri() handles empty key"() {
        when:
        def uri = path('').toUri()

        then:
        uri.scheme == 'lamin-s3'
        uri.host == 'my-bucket'
    }

    // ==================== toStorageUri ====================

    def "toStorageUri() renders the path with the s3:// scheme"() {
        expect:
        path('prefix/file.txt').toStorageUri() == 's3://my-bucket/prefix/file.txt'
    }

    def "toStorageUri() handles a key with a dot-directory"() {
        expect:
        path('9fm7UN13/.lamindb/abc0000.yaml').toStorageUri() == 's3://my-bucket/9fm7UN13/.lamindb/abc0000.yaml'
    }

    // ==================== toAbsolutePath / toRealPath ====================

    def "toAbsolutePath() returns self"() {
        given:
        def p = path('a/b')

        expect:
        p.toAbsolutePath().is(p)
    }

    def "toRealPath() returns self"() {
        given:
        def p = path('a/b')

        expect:
        p.toRealPath().is(p)
    }

    // ==================== iterator ====================

    def "iterator() returns one path per segment"() {
        when:
        def paths = path('a/b/c').collect()

        then:
        paths.size() == 3
        (paths[0] as LaminS3Path).key == 'a'
        (paths[1] as LaminS3Path).key == 'b'
        (paths[2] as LaminS3Path).key == 'c'
    }

    def "iterator() is empty for empty key"() {
        expect:
        path('').collect().isEmpty()
    }

    // ==================== compareTo ====================

    def "compareTo() is consistent with URI string ordering"() {
        given:
        def p1 = path('a/b')
        def p2 = path('a/c')

        expect:
        p1.compareTo(p2) < 0
        p2.compareTo(p1) > 0
        p1.compareTo(p1) == 0
    }

    // ==================== equals / hashCode ====================

    def "equals() returns true for same bucket and key"() {
        expect:
        path('a/b') == path('a/b')
    }

    def "equals() returns false for different key"() {
        expect:
        path('a/b') != path('a/c')
    }

    def "equals() returns false for non-LaminS3Path"() {
        expect:
        !path('a/b').equals('a/b')
    }

    def "hashCode() is stable across equal paths"() {
        expect:
        path('a/b').hashCode() == path('a/b').hashCode()
    }

    // ==================== toString ====================

    def "toString() produces the correct lamin-s3:// URI string"() {
        expect:
        path('dir/file.txt').toString() == 'lamin-s3://my-bucket/dir/file.txt'
        path('').toString() == 'lamin-s3://my-bucket/'
    }

    // ==================== WatchService (unsupported) ====================

    def "register(WatchService, kinds, modifiers) throws UnsupportedOperationException"() {
        when:
        path('a').register(Mock(WatchService), [] as java.nio.file.WatchEvent.Kind<?>[])

        then:
        thrown(UnsupportedOperationException)
    }

    def "register(WatchService, kinds) throws UnsupportedOperationException"() {
        when:
        path('a').register(Mock(WatchService))

        then:
        thrown(UnsupportedOperationException)
    }
}
