package com.example.streaming

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.example.streaming.ValidationResult.Reason
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/** Body content type declared for a successful stream (Req 3.3). */
private const val OCTET_STREAM = "application/octet-stream"

/**
 * Orchestrates the streaming pipeline: parse the API Gateway proxy event, validate the
 * requested file name, confirm the S3 object, then write the streaming response. Owns the
 * top-level mapping of every outcome to an HTTP status code (400/404/502/200) and the
 * status-committed-early ordering rule.
 *
 * Collaborators are injected by delegation (per `tech.md`): each is held in a `by lazy`
 * property fed by a factory lambda, so production uses the real defaults and tests can
 * substitute mocks without the handler depending on a wiring framework. Lazy initialization
 * also defers building the S3 client until the first real invocation.
 *
 * Ordering guarantees:
 *  - No body bytes are written on any pre-commit error path — a parse failure, a validation
 *    rejection (which issues NO S3 request), a not-found, or a head failure/timeout all
 *    produce a metadata-only error response (Req 1.2/1.3/1.4, 2.7, 3.2/3.4).
 *  - Existence/size is confirmed before any metadata is written (head-before-commit).
 *  - Once metadata + the 8 null-byte delimiter are written the status is committed; a later
 *    failure can only truncate the body and propagate, never rewrite the status (Req 6.3).
 */
class StreamHandler(
    parser: () -> RequestParser = ::RequestParser,
    validator: () -> FileNameValidator = ::FileNameValidator,
    s3Source: () -> S3Source = ::S3Source,
    responseWriter: () -> ResponseWriter = ::ResponseWriter,
) : RequestStreamHandler {

    private val parser: RequestParser by lazy(parser)
    private val validator: FileNameValidator by lazy(validator)
    private val s3Source: S3Source by lazy(s3Source)
    private val responseWriter: ResponseWriter by lazy(responseWriter)

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        when (val parsed = parser.parse(input)) {
            is ParseResult.ParseError -> {
                logger.warn { "API Gateway proxy event could not be parsed; responding 400" }
                responseWriter.writeError(output, 400, "The request could not be parsed.")
            }

            is ParseResult.Parsed -> handleValidated(parsed.request, output)
        }
    }

    private fun handleValidated(request: StreamRequest, output: OutputStream) {
        when (val validation = validator.validate(request.fileName)) {
            is ValidationResult.Invalid -> writeValidationError(validation.reason, output)
            is ValidationResult.Valid -> handleObject(validation.fileName, output)
        }
    }

    /** Maps every rejection [Reason] to HTTP 400. No S3 request is issued for a rejected name (Req 2.7). */
    private fun writeValidationError(reason: Reason, output: OutputStream) {
        val message = when (reason) {
            Reason.MISSING -> "The file name is missing."
            Reason.TOO_LONG -> "The file name is invalid."
            Reason.ILLEGAL_CHARACTER,
            Reason.PATH_SEPARATOR,
            Reason.PARENT_DIR,
            Reason.ABSOLUTE_PATH,
            -> "The file name was rejected."
        }
        logger.warn { "File name rejected (reason=$reason); responding 400 with no S3 request" }
        responseWriter.writeError(output, 400, message)
    }

    private fun handleObject(key: String, output: OutputStream) {
        when (val head = runBlocking { s3Source.head(key) }) {
            is HeadResult.NotFound -> {
                logger.warn { "Requested object not found; responding 404" }
                responseWriter.writeError(output, 404, "The requested object was not found.")
            }

            is HeadResult.Failure -> {
                logger.warn(head.cause) { "S3 head failed or timed out; responding 502" }
                responseWriter.writeError(output, 502, "Object retrieval failed.")
            }

            is HeadResult.Exists -> streamObject(key, head.size, output)
        }
    }

    /**
     * Writes the success response: metadata (status 200, `Content-Length` = [size],
     * `Content-Type: application/octet-stream`) before any body, then streams the body
     * through the bounded buffer flushing per chunk. The status is committed once
     * [ResponseWriter.writeMetadata] returns; a body failure after that only truncates the
     * body and propagates (Req 4.1, 3.3, 6.3).
     */
    private fun streamObject(key: String, size: Long, output: OutputStream) {
        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to listOf(OCTET_STREAM),
                "Content-Length" to listOf(size.toString()),
            ),
        )
        responseWriter.writeMetadata(output, metadata)
        // Status committed. From here a failure can only truncate the body — never rewrite the status.
        runCatching {
            runBlocking { s3Source.streamBody(key, output) { output.flush() } }
        }.onFailure { e ->
            logger.error(e) { "Body stream failed after status commit; response body truncated" }
        }.getOrThrow()
    }
}
