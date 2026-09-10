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

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest

/**
 * Uploads a local file to S3: a single PutObject for small files, a multipart upload above
 * {@link #multipartThreshold}.
 *
 * Parts are fed from a content provider rather than a stream so the SDK's own retries can
 * re-read a part. The part size grows with the file so that the upload stays under S3's limit
 * of 10 000 parts.
 */
@Slf4j
@CompileStatic
class LaminS3Uploader {

    static final long MIB = 1024L * 1024

    /** Files at least this large are uploaded in parts. */
    long multipartThreshold = 100 * MIB

    /** Smallest part size; S3 requires at least 5 MiB for all but the last part. */
    long minPartSize = 8 * MIB

    /** S3 allows at most 10 000 parts per upload. */
    int maxParts = 10_000

    /**
     * Upload a local file to {@code bucket/key}.
     *
     * @throws IOException if the upload fails; a multipart upload is aborted first so no parts
     *         are left behind to be billed for
     */
    void upload(AwsS3Client client, String bucket, String key, Path file) throws IOException {
        long size = Files.size(file)
        if (size < multipartThreshold) {
            log.trace "Uploading ${file} to s3://${bucket}/${key} (${size} bytes)"
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file))
            return
        }
        uploadMultipart(client, bucket, key, file, size)
    }

    /**
     * The part size to use for a file of the given size: {@link #minPartSize}, or a whole number
     * of MiB large enough to fit the file in {@link #maxParts} parts.
     */
    long partSizeFor(long size) {
        long needed = (long) Math.ceil(size / (double) maxParts)
        if (needed <= minPartSize) {
            return minPartSize
        }
        return (long) Math.ceil(needed / (double) MIB) * MIB
    }

    private void uploadMultipart(AwsS3Client client, String bucket, String key, Path file, long size) throws IOException {
        long partSize = partSizeFor(size)
        log.debug "Uploading ${file} to s3://${bucket}/${key} in parts (${size} bytes, ${partSize} bytes per part)"

        String uploadId = client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()
        ).uploadId()

        try {
            List<CompletedPart> completed = []
            int partNumber = 1
            for (long offset = 0; offset < size; offset += partSize) {
                long length = Math.min(partSize, size - offset)
                UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .partNumber(partNumber).contentLength(length)
                    .build()
                RequestBody body = RequestBody.fromContentProvider(
                    new FileRangeProvider(file, offset, length), length, 'application/octet-stream'
                )
                String eTag = client.uploadPart(request, body).eTag()
                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build())
                partNumber++
            }

            client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build()
            )
        }
        catch (Exception e) {
            log.debug "Aborting multipart upload ${uploadId} of s3://${bucket}/${key}: ${e.message}"
            try {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build()
                )
            }
            catch (Exception abortError) {
                log.warn "Could not abort multipart upload ${uploadId} of s3://${bucket}/${key}: ${abortError.message}"
            }
            throw new IOException("Failed to upload ${file} to s3://${bucket}/${key}", e)
        }
    }

    /**
     * Streams one range of a file, so the SDK can open a fresh stream when it retries a part.
     */
    @CompileStatic
    private static class FileRangeProvider implements ContentStreamProvider {
        private final Path file
        private final long offset
        private final long length

        FileRangeProvider(Path file, long offset, long length) {
            this.file = file
            this.offset = offset
            this.length = length
        }

        @Override
        InputStream newStream() {
            InputStream stream = Files.newInputStream(file)
            long skipped = 0
            while (skipped < offset) {
                long n = stream.skip(offset - skipped)
                if (n <= 0) {
                    throw new IOException("Could not seek to offset ${offset} in ${file}")
                }
                skipped += n
            }
            return new BoundedInputStream(stream, length)
        }
    }

    /**
     * Reads at most {@code limit} bytes from the wrapped stream.
     */
    @CompileStatic
    private static class BoundedInputStream extends FilterInputStream {
        private long remaining

        BoundedInputStream(InputStream in, long limit) {
            super(in)
            this.remaining = limit
        }

        @Override
        int read() throws IOException {
            if (remaining <= 0) return -1
            int b = super.read()
            if (b >= 0) remaining--
            return b
        }

        @Override
        int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) return -1
            int n = super.read(buffer, off, (int) Math.min(len, remaining))
            if (n > 0) remaining -= n
            return n
        }

        @Override
        int available() throws IOException {
            return (int) Math.min(super.available(), remaining)
        }
    }
}
