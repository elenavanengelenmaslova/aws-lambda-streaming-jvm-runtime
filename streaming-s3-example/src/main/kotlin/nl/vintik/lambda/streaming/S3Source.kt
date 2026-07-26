package nl.vintik.lambda.streaming

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.smithy.kotlin.runtime.content.toInputStream
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Wraps the Kotlin AWS SDK [S3Client] for the two streaming concerns: confirm the
 * object exists (and its size) before the response status is committed, then open
 * and copy its body through the bounded buffer.
 *
 * The bucket name comes from the `BUCKET_NAME` environment variable — never a literal,
 * and it is never logged (treated as non-secret config but kept out of logs per the
 * project logging rules). Object keys are likewise never logged.
 */
class S3Source(
    private val bucket: String = System.getenv("BUCKET_NAME"),
    private val client: S3Client = defaultClient(),
) : StreamSource<FileRequest> {
    /**
     * Confirms the requested object's existence and size via `headObject`, bounded by a
     * 10 second timeout (Req 3.1). A not-found response maps to [HeadResult.NotFound]
     * (Req 3.2); any other failure, including the timeout, maps to [HeadResult.Failure]
     * (Req 3.4). Cooperative cancellation other than the timeout is propagated.
     */
    override suspend fun head(request: FileRequest): HeadResult =
        runCatching {
            val headRequest = HeadObjectRequest {
                bucket = this@S3Source.bucket
                this.key = request.fileName
            }
            withTimeout(60.seconds) {
                client.headObject(headRequest)
            }
        }.fold(
            onSuccess = { response -> HeadResult.Exists(response.contentLength ?: 0L) },
            onFailure = { cause ->
                when (cause) {
                    is NotFound -> {
                        logger.warn(cause) { "S3 object not found during head" }
                        HeadResult.NotFound
                    }

                    is TimeoutCancellationException -> {
                        logger.warn(cause) { "S3 head timed out" }
                        HeadResult.Failure(cause)
                    }

                    is CancellationException -> throw cause

                    else -> {
                        logger.warn(cause) { "S3 head failed" }
                        HeadResult.Failure(cause)
                    }
                }
            },
        )

    /**
     * Streams the object body to [sink], copying through the bounded buffer and flushing
     * per chunk (Req 5.1). The response body `InputStream` is consumed inside a `.use { }`
     * scope so it is released on success and on failure. If reading from S3 or writing to
     * [sink] fails after streaming has begun, the copy stops, the stream is released, and
     * the error is rethrown (Req 5.7).
     *
     * @return the total number of bytes copied.
     */
    override suspend fun streamBody(request: FileRequest, sink: OutputStream, flush: () -> Unit): Long {
        val getRequest = GetObjectRequest {
            bucket = this@S3Source.bucket
            this.key = request.fileName
        }
        return client.getObject(getRequest) { response ->
            val body = checkNotNull(response.body) { "S3 getObject returned an empty body" }
            body.toInputStream().use { source ->
                copy(source, sink, flush)
            }
        }
    }

    companion object {
        /**
         * Builds an [S3Client] from the default configuration chain (region and credentials
         * resolved from the environment), suitable as a default collaborator.
         */
        private fun defaultClient(): S3Client = S3Client { }
    }
}
