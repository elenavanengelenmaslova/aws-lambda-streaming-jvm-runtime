package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/** Body content type declared for a successful stream (Req 3.3). */
private const val OCTET_STREAM = "application/octet-stream"

/**
 * Orchestrates the streaming pipeline: resolve the typed request [R], confirm the resource
 * exists, then write the streaming response.
 * Owns the top-level mapping of every outcome to an HTTP status code (400/404/502/200) and
 * the status-committed-early ordering rule.
 *
 * Collaborators are injected by delegation: each is held in a `by lazy` property fed by a
 * factory lambda, so production uses the real defaults and tests can substitute mocks without
 * the handler depending on a wiring framework. Lazy initialization also defers building the
 * source client until the first real invocation.
 *
 * Ordering guarantees:
 *  - No body bytes are written on any pre-commit error path — a request resolution error
 *    (parse failure, validation rejection) or a not-found / head failure / timeout all
 *    produce a metadata-only error response (Req 1.2/1.3/1.4, 2.7, 3.2/3.4).
 *  - Existence/size is confirmed before any metadata is written (head-before-commit).
 *  - Once metadata + the 8 null-byte delimiter are written the status is committed; a later
 *    failure can only truncate the body and propagate, never rewrite the status (Req 6.3).
 *  - The output stream is explicitly flushed then closed by this handler before returning,
 *    finalising the streaming response. Callers must not close it themselves.
 */
class StreamHandler<R : Any>(
    requestResolver: () -> RequestResolver<R>,
    source: () -> StreamSource<R>,
    responseWriter: () -> ResponseWriter = ::ResponseWriter,
) : RequestStreamHandler {

    private val requestResolver: RequestResolver<R> by lazy(requestResolver)
    private val source: StreamSource<R> by lazy(source)
    private val responseWriter: ResponseWriter by lazy(responseWriter)

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        output.use {
            when (val result = requestResolver.resolve(input)) {
                is RequestResult.Error -> responseWriter.writeError(output, result.statusCode, result.message)
                is RequestResult.Resolved -> handleObject(result.request, output)
            }
            output.flush()
        }
    }

    private fun handleObject(request: R, output: OutputStream) {
        when (val head = runBlocking { source.head(request) }) {
            is HeadResult.NotFound -> {
                logger.warn { "Requested resource not found; responding 404" }
                responseWriter.writeError(output, 404, "The requested object was not found.")
            }

            is HeadResult.Failure -> {
                logger.warn(head.cause) { "Source head failed or timed out; responding 502" }
                responseWriter.writeError(output, 502, "Object retrieval failed.")
            }

            is HeadResult.Exists -> streamObject(request, head.size, output)
        }
    }

    /**
     * Writes the success response: metadata (status 200, `Content-Length` = [size],
     * `Content-Type: application/octet-stream`) before any body, then streams the body
     * through the bounded buffer flushing per chunk. The status is committed once
     * [ResponseWriter.writeMetadata] returns; a body failure after that only truncates the
     * body and propagates (Req 4.1, 3.3, 6.3).
     */
    private fun streamObject(request: R, size: Long, output: OutputStream) {
        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to OCTET_STREAM,
                "Content-Length" to size.toString(),
            ),
        )
        responseWriter.writeMetadata(output, metadata)
        // Status committed. From here a failure can only truncate the body — never rewrite the status.
        runCatching {
            runBlocking { source.streamBody(request, output) { output.flush() } }
        }.onFailure { e ->
            logger.error(e) { "Body stream failed after status commit; response body truncated" }
        }.getOrThrow()
    }
}
