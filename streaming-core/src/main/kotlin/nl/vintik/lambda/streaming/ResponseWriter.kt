package nl.vintik.lambda.streaming

import kotlinx.serialization.json.Json
import java.io.OutputStream

/** Length of the metadata/body delimiter — 8 zero-valued bytes (Req 4.1). */
public const val DELIMITER_LEN: Int = 8

/**
 * Content-type marker that AWS's Node.js `awslambda.HttpResponseStream.from()` helper applies to
 * tag a stream as carrying the metadata-prelude protocol.
 *
 * Exposed for reference only. The Node helper sets it by calling `setContentType` on the
 * underlying stream; the JVM `RequestStreamHandler` signature offers no equivalent hook, since the
 * `OutputStream` handed to a handler carries no content-type channel. Writing the prelude, the
 * delimiter and the body in the documented byte order is therefore both necessary and sufficient
 * on the JVM.
 */
public const val METADATA_PRELUDE_CONTENT_TYPE: String =
    "application/vnd.awslambda.http-integration-response"

/**
 * A prelude length beyond which [ResponseWriter] can be asked to fail fast.
 *
 * This is **not** an AWS-documented limit. The reference implementation
 * (`awslambda.HttpResponseStream.from`) validates nothing — it serializes the prelude and writes
 * whatever it is given. This value is the commonly cited 16 KiB budget less [DELIMITER_LEN],
 * offered for callers who would rather raise [MetadataTooLargeException] than let an oversized
 * prelude reach the runtime, which may reject it or silently fold it into the response body.
 *
 * Opt in by passing it as `maxPreludeLen` to the [ResponseWriter] constructor. Left unset, the
 * writer imposes no limit and matches the reference implementation's behaviour.
 */
public const val OBSERVED_MAX_PRELUDE_LEN: Int = 16_376

/**
 * Writes the API Gateway streaming response protocol — a metadata JSON document, then the
 * 8 null-byte delimiter, then body bytes — and owns metadata/error serialization.
 *
 * The HTTP status code is committed the instant [writeMetadata] finishes writing the metadata
 * JSON and the delimiter. This writer holds no status state of its own: write failures simply
 * propagate, so an already-committed status is never mutated (Req 4.5/4.6). Failures are not
 * logged here — every one of them reaches the caller, which holds the request context needed to
 * describe it usefully.
 *
 * @param json encoder used for the prelude and for [writeError] bodies.
 * @param maxPreludeLen when non-null, a serialized prelude longer than this many bytes raises
 *   [MetadataTooLargeException] **before** anything is written, leaving the stream untouched and
 *   the status uncommitted. See [OBSERVED_MAX_PRELUDE_LEN]. Null (the default) imposes no limit.
 */
public class ResponseWriter(
    private val json: Json = Json,
    private val maxPreludeLen: Int? = null,
) {

    /**
     * Writes segments 1 + 2 of the protocol: the [metadata] JSON as UTF-8 bytes followed by a
     * [DELIMITER_LEN]-byte run of zero bytes (Req 4.1, 4.3). Once this returns the status is
     * committed; the body is streamed separately. A write failure propagates (Req 4.6).
     *
     * The encoded prelude never contains a raw NUL byte — the JSON encoder emits U+0000 as a
     * six-character escape sequence rather than a zero byte — so the first run of [DELIMITER_LEN]
     * zero bytes in the output unambiguously marks the metadata/body boundary, as the protocol
     * requires.
     *
     * @throws MetadataTooLargeException if a `maxPreludeLen` was configured and the serialized
     *   prelude exceeds it. Nothing is written in that case.
     */
    public fun writeMetadata(output: OutputStream, metadata: ResponseMetadata) {
        val prelude = encodePrelude(metadata)
        output.write(prelude)
        output.write(ByteArray(DELIMITER_LEN))
    }

    /**
     * Writes a complete response in one call: metadata, delimiter, then [body] if given, and
     * flushes. Use this for responses already held in memory; stream larger payloads by calling
     * [writeMetadata] and then [copy].
     *
     * Passing a null [body] writes a metadata-only response — the prelude and delimiter with no
     * bytes after it.
     *
     * @throws MetadataTooLargeException if a `maxPreludeLen` was configured and the serialized
     *   prelude exceeds it. Nothing is written in that case.
     */
    public fun writeResponse(
        output: OutputStream,
        metadata: ResponseMetadata,
        body: ByteArray? = null,
    ) {
        writeMetadata(output, metadata)
        if (body != null) output.write(body)
        output.flush()
    }

    /**
     * Writes a metadata-only error response: metadata for [status] followed by an [ErrorBody]
     * JSON body carrying [message], and no file body bytes (Req 1.2/1.3/1.4, 2.7, 3.2/3.4).
     *
     * This is a convenience with a fixed shape — `Content-Type: application/json` and a
     * `{"message":...}` body. Callers needing a different error representation should build it
     * themselves and call [writeResponse].
     */
    public fun writeError(output: OutputStream, status: Int, message: String) {
        writeResponse(
            output = output,
            metadata = ResponseMetadata(
                statusCode = status,
                headers = mapOf("Content-Type" to "application/json"),
            ),
            body = json.encodeToString(ErrorBody(message)).toByteArray(Charsets.UTF_8),
        )
    }

    /** Serializes [metadata] and enforces `maxPreludeLen` before any byte reaches the stream. */
    private fun encodePrelude(metadata: ResponseMetadata): ByteArray {
        val prelude = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)
        val limit = maxPreludeLen
        if (limit != null && prelude.size > limit) {
            throw MetadataTooLargeException(
                "Serialized metadata prelude is ${prelude.size} bytes, " +
                    "exceeding the configured limit of $limit bytes",
            )
        }
        return prelude
    }
}
