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

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

import software.amazon.awssdk.services.s3.model.HeadObjectResponse

/**
 * Minimal BasicFileAttributes implementation backed by an S3 HeadObject response, or
 * describing a prefix that has objects under it (a "directory").
 */
@CompileStatic
class LaminS3FileAttributes implements BasicFileAttributes {

    private final HeadObjectResponse response

    LaminS3FileAttributes(HeadObjectResponse response) {
        this.response = response
    }

    /**
     * Attributes of a prefix. S3 has no directories, so there is nothing to report but that
     * it is one.
     */
    static LaminS3FileAttributes directory() {
        return new LaminS3FileAttributes(null)
    }

    @Override
    FileTime lastModifiedTime() {
        return response?.lastModified() ? FileTime.from(response.lastModified()) : FileTime.fromMillis(0)
    }

    @Override
    FileTime lastAccessTime() {
        return lastModifiedTime()
    }

    @Override
    FileTime creationTime() {
        return lastModifiedTime()
    }

    @Override
    boolean isRegularFile() {
        return response != null
    }

    @Override
    boolean isDirectory() {
        return response == null
    }

    @Override
    boolean isSymbolicLink() {
        return false
    }

    @Override
    boolean isOther() {
        return false
    }

    @Override
    long size() {
        return response?.contentLength() ?: 0L
    }

    @Override
    Object fileKey() {
        return response?.eTag()
    }
}
