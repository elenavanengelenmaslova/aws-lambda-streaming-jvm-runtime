package com.example.streaming

import java.io.OutputStream

/**
 * Contract for a data source that can confirm a resource's existence and stream its body.
 *
 * [head] must be called before [streamBody]: it confirms the resource exists and returns its
 * size so the response metadata (`Content-Length`) can be written before any body bytes are
 * sent. Once [HeadResult.Exists] is returned, [streamBody] copies the body through the
 * bounded buffer, calling [flush] after each chunk for progressive delivery.
 *
 * Implementations: [S3Source] for S3-backed objects. Any other backing store (HTTP proxy,
 * database, local filesystem) can implement this interface and be wired into [StreamHandler]
 * without changing the orchestration or streaming-protocol logic.
 */
interface StreamSource {
    /**
     * Confirms the resource identified by [key] exists and returns its size.
     *
     * Returns [HeadResult.Exists] with the size on success, [HeadResult.NotFound] if the
     * resource does not exist, or [HeadResult.Failure] for any other error or timeout.
     */
    suspend fun head(key: String): HeadResult

    /**
     * Streams the body of the resource identified by [key] to [sink], calling [flush] after
     * each buffer chunk for progressive delivery.
     *
     * Only called after [head] returns [HeadResult.Exists]. If reading or writing fails
     * mid-stream the error propagates and the body is truncated.
     *
     * @return total bytes copied.
     */
    suspend fun streamBody(key: String, sink: OutputStream, flush: () -> Unit): Long
}
