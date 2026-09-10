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

import java.nio.file.Paths

import software.amazon.awssdk.services.s3.S3Client as AwsS3Client

/**
 * Tests for LaminPathFactory
 */
class LaminPathFactoryTest extends Specification {

    def "toUriString renders a lamin-s3 path as its s3:// storage URI"() {
        given:
        def factory = new LaminPathFactory()
        def fs = new LaminS3FileSystem(Mock(LaminS3FileSystemProvider), 's3://my-bucket/prefix', Mock(AwsS3Client), 'AKID')

        expect:
        factory.toUriString(new LaminS3Path(fs, 'prefix/results/file.txt')) == 's3://my-bucket/prefix/results/file.txt'
    }

    def "should return null for non-lamin URIs"() {
        given:
        def factory = new LaminPathFactory()

        expect:
        factory.parseUri(uri) == null

        where:
        uri << [
            's3://bucket/key',
            'gs://bucket/key',
            'file:///path/to/file',
            '/local/path',
            'https://example.com/file',
            null,
            ''
        ]
    }

    def "toUriString should return null for non-LaminPath"() {
        given:
        def factory = new LaminPathFactory()

        expect:
        factory.toUriString(Paths.get('/local/path')) == null
    }

    def "getBashLib should return null"() {
        given:
        def factory = new LaminPathFactory()

        expect:
        factory.getBashLib(Paths.get('/any/path')) == null
    }

    def "getUploadCmd should return null"() {
        given:
        def factory = new LaminPathFactory()

        expect:
        factory.getUploadCmd('/source/file', Paths.get('/any/path')) == null
    }
}
