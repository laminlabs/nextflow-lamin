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

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials

import ai.lamin.nf_lamin.hub.CloudAccessResponse

class LaminCloudCredentialsProviderTest extends Specification {

    static CloudAccessResponse access(String keyId) {
        new CloudAccessResponse([
            Credentials: [AccessKeyId: keyId, SecretAccessKey: "secret-${keyId}".toString(), SessionToken: "token-${keyId}".toString()],
            StorageAccessibility: [storageRoot: 's3://bucket/root', role: 'write', isManaged: true],
        ])
    }

    def "resolves the credentials the source currently holds"() {
        given:
        def responses = [access('AKID1'), access('AKID2')].iterator()
        def provider = new LaminCloudCredentialsProvider('s3://bucket/root', { responses.next() })

        when:
        def first = provider.resolveCredentials() as AwsSessionCredentials
        def second = provider.resolveCredentials() as AwsSessionCredentials

        then:
        first.accessKeyId() == 'AKID1'
        first.secretAccessKey() == 'secret-AKID1'
        first.sessionToken() == 'token-AKID1'
        second.accessKeyId() == 'AKID2'
    }

    def "fails when the source has no usable credentials"() {
        given:
        def provider = new LaminCloudCredentialsProvider('s3://bucket/root', { new CloudAccessResponse([:]) })

        when:
        provider.resolveCredentials()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('s3://bucket/root')
    }
}
