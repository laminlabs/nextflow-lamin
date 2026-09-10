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
import spock.lang.Unroll

class LaminUriParserTest extends Specification {

    def "should parse basic artifact URI"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/s3rtK8wIzJNKvg5Q')

        then:
        parsed.owner == 'laminlabs'
        parsed.instance == 'lamindata'
        parsed.resourceType == 'artifact'
        parsed.resourceId == 's3rtK8wIzJNKvg5Q'
        parsed.subPath == null
        !parsed.hasSubPath()
    }

    def "should parse artifact URI with sub-path"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/s3rtK8wIzJNKvg5Q/subdir/file.txt')

        then:
        parsed.owner == 'laminlabs'
        parsed.instance == 'lamindata'
        parsed.resourceType == 'artifact'
        parsed.resourceId == 's3rtK8wIzJNKvg5Q'
        parsed.subPath == 'subdir/file.txt'
        parsed.hasSubPath()
    }

    def "should parse URI object"() {
        when:
        def uri = new URI('lamin://laminlabs/lamindata/artifact/uid12345678')
        def parsed = LaminUriParser.parse(uri)

        then:
        parsed.owner == 'laminlabs'
        parsed.instance == 'lamindata'
        parsed.resourceType == 'artifact'
        parsed.resourceId == 'uid12345678'
    }

    def "should return correct instance slug"() {
        when:
        def parsed = LaminUriParser.parse('lamin://myorg/myinstance/artifact/uid123')

        then:
        parsed.instanceSlug == 'myorg/myinstance'
    }

    def "should convert back to URI string"() {
        given:
        def original = 'lamin://laminlabs/lamindata/artifact/s3rtK8wIzJNKvg5Q'

        when:
        def parsed = LaminUriParser.parse(original)

        then:
        parsed.toUriString() == original
    }

    def "should convert URI with sub-path back to string"() {
        given:
        def original = 'lamin://laminlabs/lamindata/artifact/uid123/path/to/file.txt'

        when:
        def parsed = LaminUriParser.parse(original)

        then:
        parsed.toUriString() == original
    }

    def "should get filename from URI without sub-path"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')

        then:
        parsed.fileName == 'uid123'
    }

    def "should get filename from URI with sub-path"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123/path/to/file.txt')

        then:
        parsed.fileName == 'file.txt'
    }

    def "should get parent from URI with sub-path"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123/path/to/file.txt')
        def parent = parsed.parent

        then:
        parent.subPath == 'path/to'
        parent.resourceId == 'uid123'
    }

    def "should get parent from URI with single sub-path component"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123/file.txt')
        def parent = parsed.parent

        then:
        parent.subPath == null
        parent.resourceId == 'uid123'
    }

    def "should return null parent for URI without sub-path"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')

        then:
        parsed.parent == null
    }

    def "should append sub-path"() {
        given:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')

        when:
        def withPath = parsed.withSubPath('subdir/file.txt')

        then:
        withPath.subPath == 'subdir/file.txt'
        withPath.toUriString() == 'lamin://laminlabs/lamindata/artifact/uid123/subdir/file.txt'
    }

    def "should append to existing sub-path"() {
        given:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123/existing')

        when:
        def withPath = parsed.withSubPath('more/path')

        then:
        withPath.subPath == 'existing/more/path'
    }

    def "should remove sub-path"() {
        given:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123/path/to/file.txt')

        when:
        def withoutPath = parsed.withoutSubPath()

        then:
        withoutPath.subPath == null
        withoutPath.toUriString() == 'lamin://laminlabs/lamindata/artifact/uid123'
    }

    def "should implement equals correctly"() {
        given:
        def uri1 = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')
        def uri3 = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid456')

        expect:
        uri1 == uri2
        uri1 != uri3
    }

    def "should implement hashCode correctly"() {
        given:
        def uri1 = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')
        def uri2 = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')

        expect:
        uri1.hashCode() == uri2.hashCode()
    }

    // Error cases

    def "should throw on null URI string"() {
        when:
        LaminUriParser.parse((String) null)

        then:
        thrown(IllegalArgumentException)
    }

    def "should throw on empty URI string"() {
        when:
        LaminUriParser.parse('')

        then:
        thrown(IllegalArgumentException)
    }

    def "should throw on null URI object"() {
        when:
        LaminUriParser.parse((URI) null)

        then:
        thrown(IllegalArgumentException)
    }

    def "should throw on wrong scheme"() {
        when:
        LaminUriParser.parse('s3://bucket/key')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Invalid scheme")
    }

    def "should throw on missing components"() {
        when:
        LaminUriParser.parse('lamin://laminlabs/lamindata/artifact')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Invalid URI format")
    }

    def "should throw on unsupported resource type"() {
        when:
        LaminUriParser.parse('lamin://laminlabs/lamindata/collection/uid123')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unsupported resource type")
    }

    // Storage URIs (publish targets)

    def "should parse bare instance URI as storage target"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata')

        then:
        parsed.kind == LaminUriKind.STORAGE
        parsed.isStorage()
        !parsed.isArtifact()
        parsed.owner == 'laminlabs'
        parsed.instance == 'lamindata'
        parsed.spaceUid == null
        parsed.storageUid == null
        parsed.prefix == null
        parsed.resourceType == null
        parsed.resourceId == null
    }

    def "should parse artifact URI as artifact kind"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123')

        then:
        parsed.kind == LaminUriKind.ARTIFACT
        parsed.isArtifact()
        !parsed.isStorage()
        parsed.spaceUid == null
        parsed.storageUid == null
        parsed.prefix == null
    }

    def "should parse storage URI with space, storage and prefix"() {
        when:
        def parsed = LaminUriParser.parse('lamin://laminlabs/lamindata?space=Sp4ceUid00001&storage=JwMEKs04D9WJ&prefix=results/run1')

        then:
        parsed.isStorage()
        parsed.spaceUid == 'Sp4ceUid00001'
        parsed.storageUid == 'JwMEKs04D9WJ'
        parsed.prefix == 'results/run1'
    }

    def "should parse storage URI from a URI object"() {
        when:
        def parsed = LaminUriParser.parse(new URI('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&prefix=results'))

        then:
        parsed.isStorage()
        parsed.storageUid == 'JwMEKs04D9WJ'
        parsed.prefix == 'results'
    }

    @Unroll
    def "should normalise prefix '#raw' to '#expected'"() {
        when:
        def parsed = LaminUriParser.parse("lamin://o/i?prefix=${raw}")

        then:
        parsed.prefix == expected

        where:
        raw               | expected
        'results/'        | 'results'
        '/results'        | 'results'
        'a//b/'           | 'a/b'
        'my%20results'    | 'my results'
        ''                | null
    }

    def "should render storage URI canonically"() {
        when:
        def parsed = LaminUriParser.parse('lamin://o/i?prefix=my results/x&storage=St0rage00001&space=Sp4ce0000001')

        then:
        parsed.toUriString() == 'lamin://o/i?space=Sp4ce0000001&storage=St0rage00001&prefix=my%20results/x'
    }

    def "should render bare storage URI without a query"() {
        expect:
        LaminUriParser.parse('lamin://o/i').toUriString() == 'lamin://o/i'
        LaminUriParser.parse('lamin://o/i?').toUriString() == 'lamin://o/i'
    }

    @Unroll
    def "should round-trip storage URI #uri"() {
        when:
        def parsed = LaminUriParser.parse(uri)

        then:
        LaminUriParser.parse(parsed.toUriString()) == parsed

        where:
        uri << [
            'lamin://o/i',
            'lamin://o/i?prefix=results',
            'lamin://o/i?storage=St0rage00001',
            'lamin://o/i?space=Sp4ce0000001&prefix=a%20b/c',
            'lamin://o/i?space=Sp4ce0000001&storage=St0rage00001&prefix=results/run1',
        ]
    }

    def "should distinguish storage URIs in equals and hashCode"() {
        given:
        def a = LaminUriParser.parse('lamin://o/i?storage=St0rage00001&prefix=results')
        def b = LaminUriParser.parse('lamin://o/i?storage=St0rage00001&prefix=results')
        def c = LaminUriParser.parse('lamin://o/i?storage=St0rage00002&prefix=results')
        def d = LaminUriParser.parse('lamin://o/i?storage=St0rage00001')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
        a != d
    }

    @Unroll
    def "should reject invalid storage URI #uri (#reason)"() {
        when:
        LaminUriParser.parse(uri)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(expectedMessage)

        where:
        uri                                                   | reason                 | expectedMessage
        'lamin://o/i?foo=bar'                                 | 'unknown parameter'    | "Unknown query parameter 'foo'"
        'lamin://o/i?prefix=a&prefix=b'                       | 'duplicate parameter'  | "Duplicate query parameter 'prefix'"
        'lamin://o/i?space='                                  | 'empty uid'            | "'space' cannot be empty"
        'lamin://o/i?prefix=.lamindb/x'                       | 'reserved prefix'      | ".lamindb"
        'lamin://o/i?prefix=a/../b'                           | 'parent segment'       | "'..'"
        'lamin://o/i/artifact/uid123?prefix=results'          | 'query on artifact'    | "Query parameters are not supported"
        'lamin://o/i/results'                                 | 'stray path segment'   | "Invalid URI format"
    }

    @Unroll
    def "should parse various valid URIs: #uri"() {
        when:
        def parsed = LaminUriParser.parse(uri)

        then:
        parsed.owner == expectedOwner
        parsed.instance == expectedInstance
        parsed.resourceId == expectedId

        where:
        uri                                                    | expectedOwner | expectedInstance | expectedId
        'lamin://org/inst/artifact/abc123'                     | 'org'         | 'inst'           | 'abc123'
        'lamin://my-org/my-instance/artifact/uid_with_under'   | 'my-org'      | 'my-instance'    | 'uid_with_under'
        'lamin://o/i/artifact/u'                               | 'o'           | 'i'              | 'u'
    }
}
