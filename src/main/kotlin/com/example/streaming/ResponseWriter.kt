package com.example.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/** Length of the metadata/body delimiter — 8 zero-valued bytes (Req 4.1). */
const val DELIMITER_LEN = 8

/**
 * Content-type marker used by the Node.js `awslambda.HttpResponseStream.from()` helper to
 * tag a stream as carrying the metadata-prelude protocol. Kept here as the canonical port
 * reference; how (or whether) it must be conveyed on the JVM `RequestStreamHandler` path is
 * an open item verified against a real deployment (see `docs/log.md`).
 */
const val METADATA_PRELUDE_CONTENT_TYPE = "application/vnd.awslambda.http-integration-response"

/**
 * Writes the API Gateway streaming response protocol — a metadata JSON document, then the
 * 8 null-byte delimiter, then body bytes — and owns metadata/error serialization.
 *
 * The HTTP status code is committed the instant [writeMetadata] finishes writing the metadata
 * JSON and the delimiter. This writer holds no status state of its own: write failures simply
 * propagate, so an already-committed status is never mutated (Req 4.5/4.6).
 */
class ResponseWriter(private val json: Json = Json) {

    /**
     * Writes segments 1 + 2 of the protocol: the [metadata] JSON as UTF-8 bytes followed by a
     * [DELIMITER_LEN]-byte run of zero bytes (Req 4.1, 4.3). Once this returns the status is
     * committed; the body is streamed separately. A write failure propagates (Req 4.6).
     */
    fun writeMetadata(output: OutputStream, metadata: ResponseMetadata): Unit =
        runCatching {
            val prelude = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)
            output.write(prelude)
            output.write(ByteArray(DELIMITER_LEN))
        }.onFailure { e ->
            logger.error(e) { "Failed writing response metadata: status=${metadata.statusCode}" }
        }.getOrThrow()

    /**
     * Writes a metadata-only error response: metadata for [status] followed by an [ErrorBody]
     * JSON body carrying [message], and no file body bytes (Req 1.2/1.3/1.4, 2.7, 3.2/3.4).
     */
    fun writeError(output: OutputStream, status: Int, message: String): Unit =
        runCatching {
            val metadata = ResponseMetadata(
                statusCode = status,
                headers = mapOf("Content-Type" to listOf("application/json")),
            )
            writeMetadata(output, metadata)
            output.write(json.encodeToString(ErrorBody(message)).toByteArray(Charsets.UTF_8))
            output.flush()
        }.onFailure { e ->
            logger.error(e) { "Failed writing error response: status=$status" }
        }.getOrThrow()
}
