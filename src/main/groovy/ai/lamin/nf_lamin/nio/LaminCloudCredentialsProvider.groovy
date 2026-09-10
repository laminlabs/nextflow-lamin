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

import java.util.function.Supplier

import groovy.transform.CompileStatic

import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials

import ai.lamin.nf_lamin.hub.CloudAccessResponse

/**
 * Hands the AWS SDK the STS credentials LaminHub currently grants on a storage root.
 *
 * The SDK asks for credentials on every request, so backing it with the per-root cloud-access
 * cache means a transfer that outlives the token it started with picks up the refreshed one
 * instead of failing with ExpiredToken.
 */
@CompileStatic
class LaminCloudCredentialsProvider implements AwsCredentialsProvider {

    private final String storageRoot
    private final Supplier<CloudAccessResponse> source

    LaminCloudCredentialsProvider(String storageRoot, Supplier<CloudAccessResponse> source) {
        this.storageRoot = storageRoot
        this.source = source
    }

    @Override
    AwsCredentials resolveCredentials() {
        CloudAccessResponse access = source.get()
        if (access == null || !access.isUsable()) {
            throw new IllegalStateException("LaminHub returned no credentials for ${storageRoot}")
        }
        return AwsSessionCredentials.create(access.accessKeyId, access.secretAccessKey, access.sessionToken)
    }
}
