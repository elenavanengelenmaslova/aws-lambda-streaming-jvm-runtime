package com.example.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.Ordering
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
 * constructor (`() -> T`), so each `head` outcome can be driven without any real parsing,
 * validation, S3, or output wiring. For all of these tests the request is parsed and
 * validated to a fixed file name, isolating the `head`-result branch under test:
 *
 * - [HeadResult.NotFound]  -> HTTP 404, no metadata and no body (Req 3.2).
 * - [HeadResult.Failure]   -> HTTP 502, no metadata and no body (Req 3.4); covers both a
 *   plain failure and the 10 s head timeout, which the source maps to the same branch.
 * - [HeadResult.Exists]    -> HTTP 200 with `Content-Length` = size and
 *   `Content-Type: application/octet-stream` written as metadata *before* any body (Req 3.3).
 *
 * Ordering is asserted with `coVerify(ordering = Ordering.ORDERED)` (covering the suspend
 * `head`/`streamBody` calls and the blocking `writeMetadata`): the object is confirmed via
 * `head` before metadata is written (head-before-commit, Req 3.1), and metadata is written
 * before the body is streamed (status-committed-early, Req 3.3).
 */
class StreamHandlerTest {

    private val parser: RequestParser = mockk(relaxed = true)
    private val validator: FileNameValidator = mockk(relaxed = true)
    private val s3Source: S3Source = mockk(relaxed = true)
    private val responseWriter: ResponseWriter = mockk(relaxed = true)

    private val input: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
    private val output: ByteArrayOutputStream = ByteArrayOutputStream()
    private val context: Context = mockk(relaxed = true)

    private val handler = StreamHandler(
        parser = { parser },
        validator = { validator },
        s3Source = { s3Source },
        responseWriter = { responseWriter },
    )

    private val fileName = "report.bin"

    @AfterEach
    fun tearDown() {
        // Only reset the actual mocks; `input`/`output` are real in-memory streams.
        clearMocks(parser, validator, s3Source, responseWriter, context)
    }

    /** Drives the pipeline to a valid, parsed request so only the `head` branch varies. */
    private fun givenValidRequest() {
        // `coEvery` is used for these non-suspend stubs so collaborator stubbing works
        // uniformly alongside the suspend `s3Source.head`/`streamBody` stubs under the
        // coverage-instrumented test runtime.
        coEvery { parser.parse(input) } returns ParseResult.Parsed(StreamRequest(fileName))
        coEvery { validator.validate(fileName) } returns ValidationResult.Valid(fileName)
    }

    // --- NotFound -> 404 (Req 3.2) ---

    @Test
    fun `Given head returns NotFound When the request is handled Then it writes 404 and no body`() {
        givenValidRequest()
        coEvery { s3Source.head(fileName) } returns HeadResult.NotFound

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 404, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { s3Source.streamBody(any(), any(), any()) }
    }

    // --- Failure -> 502 (Req 3.4) ---

    @Test
    fun `Given head returns Failure When the request is handled Then it writes 502 and no body`() {
        givenValidRequest()
        coEvery { s3Source.head(fileName) } returns HeadResult.Failure(RuntimeException("S3 unavailable"))

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 502, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { s3Source.streamBody(any(), any(), any()) }
    }

    // --- Failure carrying a timeout cause -> 502 (Req 3.1, 3.4) ---

    @Test
    fun `Given head returns Failure from a timeout When the request is handled Then it writes 502 and no body`() {
        givenValidRequest()
        // The source maps a head timeout to Failure; the handler treats any Failure as 502
        // regardless of the carried cause, so a timeout-representing cause exercises the same branch.
        coEvery { s3Source.head(fileName) } returns HeadResult.Failure(RuntimeException("S3 head timed out"))

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeError(output, 502, any()) }
        coVerify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
        coVerify(exactly = 0) { s3Source.streamBody(any(), any(), any()) }
    }

    // --- Exists(size) -> 200 with Content-Length = size, metadata before body (Req 3.3) ---

    @Test
    fun `Given head returns Exists When the request is handled Then it writes 200 metadata with the declared content length`() {
        givenValidRequest()
        val size = 15_728_640L
        coEvery { s3Source.head(fileName) } returns HeadResult.Exists(size)
        val metadataSlot = slot<ResponseMetadata>()

        handler.handleRequest(input, output, context)

        coVerify(exactly = 1) { responseWriter.writeMetadata(output, capture(metadataSlot)) }
        coVerify(exactly = 0) { responseWriter.writeError(any(), any(), any()) }

        val metadata = metadataSlot.captured
        assertEquals(200, metadata.statusCode)
        assertEquals(listOf(size.toString()), metadata.headers["Content-Length"])
        assertEquals(listOf("application/octet-stream"), metadata.headers["Content-Type"])
    }

    @Test
    fun `Given head returns Exists When the request is handled Then head precedes metadata and metadata precedes the body`() {
        givenValidRequest()
        val size = 4096L
        coEvery { s3Source.head(fileName) } returns HeadResult.Exists(size)

        handler.handleRequest(input, output, context)

        // head-before-commit (Req 3.1) then status-committed-early: metadata before body (Req 3.3).
        coVerify(ordering = Ordering.ORDERED) {
            s3Source.head(fileName)
            responseWriter.writeMetadata(output, any())
            s3Source.streamBody(eq(fileName), eq(output), any())
        }
    }
}
