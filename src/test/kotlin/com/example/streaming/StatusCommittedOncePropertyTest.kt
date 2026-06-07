package com.example.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.stream.Stream

/**
 * Property 5: Status is committed exactly once; failures never rewrite it.
 *
 * For all execution outcomes, once the metadata JSON and the 8 null-byte delimiter have been
 * fully written the HTTP status code is never altered. This single `@ParameterizedTest` drives
 * [StreamHandler] (with mocked collaborators) over a mix of (outcome, failure-offset) injection
 * points along the pipeline:
 *
 *  - **Pre-commit** failures — parser returns [ParseResult.ParseError], validator returns
 *    [ValidationResult.Invalid], or `head` returns [HeadResult.NotFound] / [HeadResult.Failure]
 *    (the latter also represents the 10 s head timeout, which the source maps to `Failure`).
 *    For each: the captured response carries the mapped status (400 / 404 / 502) and **no file
 *    body bytes** are streamed (`s3Source.streamBody` is never invoked).
 *
 *  - **Post-commit** failures — `head` returns [HeadResult.Exists]`(size)` so the real
 *    [ResponseWriter] commits the 200 metadata + delimiter, then `streamBody` writes some body
 *    bytes at a varying offset and throws. For each: the committed status byte-prefix (metadata
 *    + 8 zero-byte delimiter) is **byte-identical** to what was written, the body is **truncated**
 *    (strictly fewer bytes than `size`), the source `InputStream` is **released**, and the error
 *    **propagates** out of `handleRequest`.
 *
 * The real [ResponseWriter] is used so the committed status is read back from the actual bytes
 * (decoding the metadata prefix that precedes the delimiter), rather than from a mock expectation.
 *
 * Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4, 4.5, 4.6, 5.7, 6.3
 */
@DisplayName(
    "Feature: s3-file-streaming-endpoint, Property 5: status committed once, failures never rewrite it",
)
class StatusCommittedOncePropertyTest {

    private val fileName = "object.bin"

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    fun `Given a failure injection point When the request is handled Then the status commits once and is never rewritten`(
        @Suppress("UNUSED_PARAMETER") label: String,
        scenario: Scenario,
    ) {
        val parser = mockk<RequestParser>(relaxed = true)
        val validator = mockk<FileNameValidator>(relaxed = true)
        val s3Source = mockk<S3Source>(relaxed = true)
        val responseWriter = ResponseWriter() // real writer: bytes are actually committed
        val output = CapturingOutputStream()

        val handler = StreamHandler(
            parser = { parser },
            validator = { validator },
            s3Source = { s3Source },
            responseWriter = { responseWriter },
        )

        when (scenario.kind) {
            Kind.PARSE_ERROR -> {
                every { parser.parse(any()) } returns ParseResult.ParseError
            }

            Kind.VALIDATION_INVALID -> {
                every { parser.parse(any()) } returns ParseResult.Parsed(StreamRequest(fileName))
                every { validator.validate(fileName) } returns ValidationResult.Invalid(scenario.invalidReason!!)
            }

            Kind.HEAD_NOT_FOUND -> {
                givenValidRequest(parser, validator)
                coEvery { s3Source.head(fileName) } returns HeadResult.NotFound
            }

            Kind.HEAD_FAILURE -> {
                givenValidRequest(parser, validator)
                coEvery { s3Source.head(fileName) } returns HeadResult.Failure(RuntimeException("S3 head failed/timed out"))
            }

            Kind.POST_COMMIT -> {
                givenValidRequest(parser, validator)
                coEvery { s3Source.head(fileName) } returns HeadResult.Exists(scenario.size)
            }
        }

        if (scenario.kind == Kind.POST_COMMIT) {
            verifyPostCommit(scenario, s3Source, handler, output)
        } else {
            verifyPreCommit(scenario, s3Source, handler, output)
        }
    }

    /**
     * Pre-commit: the handler maps the failure to the expected status and never streams a file
     * body. The status is read back from the actually-written metadata prefix.
     */
    private fun verifyPreCommit(
        scenario: Scenario,
        s3Source: S3Source,
        handler: StreamHandler,
        output: CapturingOutputStream,
    ) {
        handler.handleRequest(eventInput(), output, mockk<Context>(relaxed = true))

        val bytes = output.toByteArray()
        assertEquals(
            scenario.expectedStatus,
            statusOf(bytes),
            "pre-commit failure must commit the mapped status",
        )
        // No file body is ever streamed on a pre-commit failure path.
        coVerify(exactly = 0) { s3Source.streamBody(any(), any(), any()) }
    }

    /**
     * Post-commit: metadata + delimiter are committed, then `streamBody` writes [Scenario.offset]
     * bytes and throws. Asserts the status byte-prefix is unchanged, the body is truncated, the
     * source stream is released, and the error propagates.
     */
    private fun verifyPostCommit(
        scenario: Scenario,
        s3Source: S3Source,
        handler: StreamHandler,
        output: CapturingOutputStream,
    ) {
        val source = TrackingInputStream(scenario.size)
        val boom = IOException("mid-stream failure at offset=${scenario.offset}")

        // Mimic the real S3Source.streamBody contract: copy some bytes, then release the source
        // stream (via .use) and rethrow on a mid-stream failure (Req 5.7).
        coEvery { s3Source.streamBody(eq(fileName), any(), any()) } answers {
            val sink = secondArg<OutputStream>()
            val flush = thirdArg<() -> Unit>()
            source.use { s ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = scenario.offset
                while (remaining > 0) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = s.read(buffer, 0, toRead)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    flush()
                    remaining -= read
                }
                throw boom
            }
        }

        // The error propagates out of handleRequest (Req 5.7, 6.3).
        val thrown = assertThrows(IOException::class.java) {
            handler.handleRequest(eventInput(), output, mockk<Context>(relaxed = true))
        }
        assertEquals(boom.message, thrown.message, "the original mid-stream error must propagate")

        val bytes = output.toByteArray()

        // The committed status byte-prefix (metadata JSON + 8 zero-byte delimiter) is unchanged.
        val expectedPrefix = committedPrefix(scenario.size)
        assertTrue(
            bytes.size >= expectedPrefix.size,
            "output must contain the full committed metadata prefix",
        )
        assertArrayEquals(
            expectedPrefix,
            bytes.copyOfRange(0, expectedPrefix.size),
            "the committed status byte-prefix must not be rewritten by a post-commit failure",
        )
        assertEquals(200, statusOf(bytes), "committed status stays 200 after a post-commit failure")

        // The body is truncated: bytes written after the delimiter are strictly fewer than size.
        val bodyLength = (bytes.size - expectedPrefix.size).toLong()
        assertEquals(scenario.offset, bodyLength, "body length equals the bytes written before the failure")
        assertTrue(
            bodyLength < scenario.size,
            "post-commit failure must truncate the body (bodyLength=$bodyLength < size=${scenario.size})",
        )

        // The source stream was released despite the failure (Req 5.7).
        assertTrue(source.closed, "the source InputStream must be released on a mid-stream failure")
    }

    private fun givenValidRequest(parser: RequestParser, validator: FileNameValidator) {
        every { parser.parse(any()) } returns ParseResult.Parsed(StreamRequest(fileName))
        every { validator.validate(fileName) } returns ValidationResult.Valid(fileName)
    }

    /** A minimal input stream — collaborators are mocked so its content is irrelevant. */
    private fun eventInput(): InputStream = ByteArrayInputStream(ByteArray(0))

    /** The exact metadata prefix the handler commits for a successful 200 stream of [size] bytes. */
    private fun committedPrefix(size: Long): ByteArray {
        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to listOf("application/octet-stream"),
                "Content-Length" to listOf(size.toString()),
            ),
        )
        return Json.encodeToString(metadata).toByteArray(Charsets.UTF_8) + ByteArray(DELIMITER_LEN)
    }

    /** Identifies which failure injection point a scenario exercises. */
    enum class Kind { PARSE_ERROR, VALIDATION_INVALID, HEAD_NOT_FOUND, HEAD_FAILURE, POST_COMMIT }

    /**
     * A single (outcome, failure-offset) injection point. [size]/[offset] are only meaningful for
     * [Kind.POST_COMMIT]; [invalidReason] only for [Kind.VALIDATION_INVALID].
     */
    data class Scenario(
        val kind: Kind,
        val expectedStatus: Int,
        val invalidReason: ValidationResult.Reason? = null,
        val size: Long = 0L,
        val offset: Long = 0L,
    )

    companion object {
        /**
         * Extracts the committed HTTP status by decoding the metadata JSON prefix that precedes the
         * 8 null-byte delimiter (segment 1 of the streaming protocol).
         */
        private fun statusOf(bytes: ByteArray): Int {
            val delimiterStart = indexOfDelimiter(bytes)
            require(delimiterStart >= 0) { "response is missing the 8 null-byte delimiter" }
            val metadataJson = bytes.copyOfRange(0, delimiterStart).decodeToString()
            return Json.decodeFromString<ResponseMetadata>(metadataJson).statusCode
        }

        /** Index of the first run of [DELIMITER_LEN] consecutive zero bytes, or -1 if absent. */
        private fun indexOfDelimiter(bytes: ByteArray): Int {
            var run = 0
            for (i in bytes.indices) {
                if (bytes[i].toInt() == 0) {
                    run++
                    if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
                } else {
                    run = 0
                }
            }
            return -1
        }

        @JvmStatic
        fun scenarios(): Stream<Arguments> = Stream.of(
            // --- Pre-commit: parse failure -> 400 (Req 1.3, 4.6) ---
            Arguments.of("pre-commit parse error -> 400", Scenario(Kind.PARSE_ERROR, 400)),

            // --- Pre-commit: validation rejection -> 400 (Req 1.2, 2.x) ---
            Arguments.of(
                "pre-commit validation MISSING -> 400",
                Scenario(Kind.VALIDATION_INVALID, 400, invalidReason = ValidationResult.Reason.MISSING),
            ),
            Arguments.of(
                "pre-commit validation TOO_LONG -> 400",
                Scenario(Kind.VALIDATION_INVALID, 400, invalidReason = ValidationResult.Reason.TOO_LONG),
            ),
            Arguments.of(
                "pre-commit validation PATH_SEPARATOR -> 400",
                Scenario(Kind.VALIDATION_INVALID, 400, invalidReason = ValidationResult.Reason.PATH_SEPARATOR),
            ),
            Arguments.of(
                "pre-commit validation ILLEGAL_CHARACTER -> 400",
                Scenario(Kind.VALIDATION_INVALID, 400, invalidReason = ValidationResult.Reason.ILLEGAL_CHARACTER),
            ),

            // --- Pre-commit: head not found -> 404 (Req 3.2) ---
            Arguments.of("pre-commit head NotFound -> 404", Scenario(Kind.HEAD_NOT_FOUND, 404)),

            // --- Pre-commit: head failure / timeout -> 502 (Req 3.1, 3.4) ---
            Arguments.of("pre-commit head Failure -> 502", Scenario(Kind.HEAD_FAILURE, 502)),

            // --- Post-commit: streamBody throws at varying offsets -> status stays 200, body truncated ---
            Arguments.of(
                "post-commit fail immediately after commit (offset 0, 1 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 1_048_576L, offset = 0L),
            ),
            Arguments.of(
                "post-commit fail after 1 byte (1 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 1_048_576L, offset = 1L),
            ),
            Arguments.of(
                "post-commit fail mid-first-chunk (512 KB of 2 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 2_097_152L, offset = 524_288L),
            ),
            Arguments.of(
                "post-commit fail after one full chunk (1 MB of 15 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 15_728_640L, offset = 1_048_576L),
            ),
            Arguments.of(
                "post-commit fail past the 6 MB limit (7 MB of 15 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 15_728_640L, offset = 7_340_032L),
            ),
            Arguments.of(
                "post-commit fail near completion (999 of 1000 bytes)",
                Scenario(Kind.POST_COMMIT, 200, size = 1000L, offset = 999L),
            ),
            Arguments.of(
                "post-commit fail mid-stream (5 MB of 10 MB)",
                Scenario(Kind.POST_COMMIT, 200, size = 10_485_760L, offset = 5_242_880L),
            ),
        )
    }

    /** A custom [OutputStream] that captures every written byte. */
    private class CapturingOutputStream : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = buffer.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    /** An [InputStream] of [size] deterministic bytes that records whether it was closed. */
    private class TrackingInputStream(size: Long) : InputStream() {
        private val delegate = ByteArrayInputStream(ByteArray(size.toInt()) { (it % 251).toByte() })
        var closed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
