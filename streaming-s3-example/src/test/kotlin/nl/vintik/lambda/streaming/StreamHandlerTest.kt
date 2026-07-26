package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.Ordering
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Branch unit tests for [StreamHandler] (Req 3.1, 3.2, 3.3, 3.4).
 *
 * Every collaborator is injected as a relaxed MockK through the handler's factory-lambda
 * constructor (`() -> T`), so each `head` outcome can be driven without any real request
 * resolution, source, or output wiring. The type parameter is [String] — a plain key string
 * is the simplest valid request type for unit tests that only exercise the head/stream branch.
 *
 * - [HeadResult.NotFound]  -> HTTP 404, no metadata and no body (Req 3.2).
 * - [HeadResult.Failure]   -> HTTP 502, no metadata and no body (Req 3.4).
 * - [HeadResult.Exists]    -> HTTP 200 with `Content-Length` = size and
 *   `Content-Type: application/octet-stream` written as metadata *before* any body (Req 3.3).
 *
 * Ordering is asserted with `coVerify(ordering = Ordering.ORDERED)`: object confirmed via
 * `head` before metadata is written (head-before-commit, Req 3.1), and metadata written
 * before the body is streamed (status-committed-early, Req 3.3).
 */
class StreamHandlerTest {

    private val requestResolver: RequestResolver<String> = mockk(relaxed = true)
    private val source: StreamSource<String> = mockk(relaxed = true)
    private val responseWriter: ResponseWriter = mockk(relaxed = true)

    private val input: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
    private val output: ByteArrayOutputStream = ByteArrayOutputStream()
    private val context: Context = mockk(relaxed = true)

    private val handler = StreamHandler(
        requestResolver = { requestResolver },
        source = { source },
        responseWriter = { responseWriter },
    )

    private val key = "report.bin"

    @AfterEach
    fun tearDown() {
        clearMocks(requestResolver, source, responseWriter, context)
    }

    /** Drives the pipeline to a resolved request so only the `head` branch varies. */
    private fun givenResolvedRequest() {
        every { requestResolver.resolve(input) } returns RequestResult.Resolved(key)
    }

    // --- RequestResult.Error -> error response (Req 1.2, 1.3) ---

    @Test
    fun `Given resolver returns Error When the request is handled Then it writes the error response and no body`() {
        every { requestResolver.resolve(input) } returns RequestResult.Error(400, "The request could not be parsed.")

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 400, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { source.head(any()) }
        coVerify(exactly = 0) { source.streamBody(any(), any(), any()) }
    }

    // --- NotFound -> 404 (Req 3.2) ---

    @Test
    fun `Given head returns NotFound When the request is handled Then it writes 404 and no body`() {
        givenResolvedRequest()
        coEvery { source.head(key) } returns HeadResult.NotFound

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 404, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { source.streamBody(any(), any(), any()) }
    }

    // --- Failure -> 502 (Req 3.4) ---

    @Test
    fun `Given head returns Failure When the request is handled Then it writes 502 and no body`() {
        givenResolvedRequest()
        coEvery { source.head(key) } returns HeadResult.Failure(RuntimeException("source unavailable"))

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 502, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { source.streamBody(any(), any(), any()) }
    }

    // --- Failure carrying a timeout cause -> 502 (Req 3.1, 3.4) ---

    @Test
    fun `Given head returns Failure from a timeout When the request is handled Then it writes 502 and no body`() {
        givenResolvedRequest()
        coEvery { source.head(key) } returns HeadResult.Failure(RuntimeException("head timed out"))

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 502, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { source.streamBody(any(), any(), any()) }
    }

    // --- Exists(size) -> 200 with Content-Length = size, metadata before body (Req 3.3) ---

    @Test
    fun `Given head returns Exists When the request is handled Then it writes 200 metadata with the declared content length`() {
        givenResolvedRequest()
        val size = 15_728_640L
        coEvery { source.head(key) } returns HeadResult.Exists(size)
        val metadataSlot = slot<ResponseMetadata>()

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeMetadata(output, capture(metadataSlot)) }
        coVerify(exactly = 0) { responseWriter.writeError(any(), any(), any()) }

        val metadata = metadataSlot.captured
        assertEquals(200, metadata.statusCode)
        assertEquals(size.toString(), metadata.headers["Content-Length"])
        assertEquals("application/octet-stream", metadata.headers["Content-Type"])
    }

    @Test
    fun `Given head returns Exists When the request is handled Then head precedes metadata and metadata precedes the body`() {
        givenResolvedRequest()
        val size = 4096L
        coEvery { source.head(key) } returns HeadResult.Exists(size)

        handler.handleRequest(input, output, context)

        // head-before-commit (Req 3.1) then status-committed-early: metadata before body (Req 3.3).
        coVerify(ordering = Ordering.ORDERED) {
            source.head(key)
            responseWriter.writeMetadata(output, any())
            source.streamBody(eq(key), eq(output), any())
        }
    }
}
