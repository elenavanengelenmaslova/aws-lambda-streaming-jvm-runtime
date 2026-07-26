package nl.vintik.lambda.streaming

import java.io.OutputStream

/**
 * Contract for a data source that can confirm a resource's existence and stream its body,
 * driven by a typed request [R].
 *
 * [head] must be called before [streamBody]: it confirms the resource exists and returns its
 * size so the response metadata (`Content-Length`) can be written before any body bytes are
 * sent. Once [HeadResult.Exists] is returned, [streamBody] copies the body through the
 * bounded buffer, calling [flush] after each chunk for progressive delivery.
 *
 * The type parameter [R] is the request object produced by [RequestResolver]. It may carry
 * a single identifier (e.g. a file name) or a richer structure (tenant ID, region, file name,
 * etc.) — whatever the source implementation needs to locate and stream the resource.
 *
 * Any backing store (S3, HTTP proxy, database cursor, local filesystem) can implement this
 * interface and be wired into [StreamHandler] without changing the orchestration or
 * streaming-protocol logic.
 */
interface StreamSource<in R : Any> {
    /**
     * Confirms the resource identified by [request] exists and returns its size.
     *
     * Returns [HeadResult.Exists] with the size on success, [HeadResult.NotFound] if the
     * resource does not exist, or [HeadResult.Failure] for any other error or timeout.
     */
    suspend fun head(request: R): HeadResult

    /**
     * Streams the body of the resource identified by [request] to [sink], calling [flush]
     * after each buffer chunk for progressive delivery.
     *
     * Only called after [head] returns [HeadResult.Exists]. If reading or writing fails
     * mid-stream the error propagates and the body is truncated.
     *
     * @return total bytes copied.
     */
    suspend fun streamBody(request: R, sink: OutputStream, flush: () -> Unit): Long
}
