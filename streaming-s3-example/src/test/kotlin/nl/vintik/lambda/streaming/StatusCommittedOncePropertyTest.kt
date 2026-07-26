package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.*
import java.util.stream.Stream

/**
 * Property 5: Status is committed exactly once; failures never rewrite it.
 *
 * For all execution outcomes, once the metadata JSON and the 8 null-byte delimiter have been
 * fully written the HTTP status code is never altered. This single `@ParameterizedTest` drives
 * [StreamHandler] (with mocked collaborators) over a mix of (outcome, failure-offset) injection
 * points along the pipeline.
 *
 * The type parameter is [String] — the simplest valid request type, letting tests focus on
 * the status-commit guarantee rather than request deserialization.
 *
 *  - **Pre-commit** failures — resolver returns [RequestResult.Error], or `head` returns
 *    [HeadResult.NotFound] / [HeadResult.Failure]. For each: the captured response carries
 *    the mapped status (400 / 404 / 502) and **no body bytes** are streamed.
 *
 *  - **Post-commit** failures — resolver returns [RequestResult.Resolved] and `head` returns
 *    [HeadResult.Exists]`(size)` so the real [ResponseWriter] commits the 200 metadata +
 *    delimiter, then `streamBody` writes some body bytes and throws. For each: the committed
 *    status byte-prefix is **byte-identical**, the body is **truncated**, and the error
 *    **propagates** out of `handleRequest`.
 *
 * Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4, 4.5, 4.6, 5.7, 6.3
 */
@DisplayName(
    "Feature: streaming-core, Property 5: status committed once, failures never rewrite it",
)
class StatusCommittedOncePropertyTest {

    private val key = "object.bin"

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    fun `Given a failure injection point When the request is handled Then the status commits once and is never rewritten`(
        @Suppress("UNUSED_PARAMETER") label: String,
        scenario: Scenario,
    ) {
        val requestResolver = mockk<RequestResolver<String>>(relaxed = true)
        val source = mockk<StreamSource<String>>(relaxed = true)
        val responseWriter = ResponseWriter()
        val output = CapturingOutputStream()

        val handler = StreamHandler(
            requestResolver = { requestResolver },
            source = { source },
            responseWriter = { responseWriter },
        )

        when (scenario.kind) {
            Kind.REQUEST_ERROR -> {
                every { requestResolver.resolve(any()) } returns RequestResult.Error(scenario.expectedStatus, "error")
            }

            Kind.HEAD_NOT_FOUND -> {
                every { requestResolver.resolve(any()) } returns RequestResult.Resolved(key)
                coEvery { source.head(key) } returns HeadResult.NotFound
            }

            Kind.HEAD_FAILURE -> {
                every { requestResolver.resolve(any()) } returns RequestResult.Resolved(key)
                coEvery { source.head(key) } returns HeadResult.Failure(RuntimeException("head failed/timed out"))
            }

            Kind.POST_COMMIT -> {
                every { requestResolver.resolve(any()) } returns RequestResult.Resolved(key)
                coEvery { source.head(key) } returns HeadResult.Exists(scenario.size)
            }
        }

        if (scenario.kind == Kind.POST_COMMIT) {
            verifyPostCommit(scenario, source, handler, output)
        } else {
            verifyPreCommit(scenario, source, handler, output)
        }
    }

    private fun verifyPreCommit(
        scenario: Scenario,
        source: StreamSource<String>,
        handler: StreamHandler<String>,
        output: CapturingOutputStream,
    ) {
        handler.handleRequest(eventInput(), output, mockk<Context>(relaxed = true))

        val bytes = output.toByteArray()
        assertEquals(
            scenario.expectedStatus,
            statusOf(bytes),
            "pre-commit failure must commit the mapped status",
        )
        coVerify(exactly = 0) { source.streamBody(any(), any(), any()) }
    }

    private fun verifyPostCommit(
        scenario: Scenario,
        source: StreamSource<String>,
        handler: StreamHandler<String>,
        output: CapturingOutputStream,
    ) {
        val trackingInput = TrackingInputStream(scenario.size)
        val boom = IOException("mid-stream failure at offset=${scenario.offset}")

        coEvery { source.streamBody(eq(key), any(), any()) } answers {
            val sink = secondArg<OutputStream>()
            val flush = thirdArg<() -> Unit>()
            trackingInput.use { s ->
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

        val thrown = assertThrows(IOException::class.java) {
            handler.handleRequest(eventInput(), output, mockk<Context>(relaxed = true))
        }
        assertEquals(boom.message, thrown.message, "the original mid-stream error must propagate")

        val bytes = output.toByteArray()
        val expectedPrefix = committedPrefix(scenario.size)
        assertTrue(bytes.size >= expectedPrefix.size, "output must contain the full committed metadata prefix")
        assertArrayEquals(
            expectedPrefix,
            bytes.copyOfRange(0, expectedPrefix.size),
            "the committed status byte-prefix must not be rewritten by a post-commit failure",
        )
        assertEquals(200, statusOf(bytes), "committed status stays 200 after a post-commit failure")

        val bodyLength = (bytes.size - expectedPrefix.size).toLong()
        assertEquals(scenario.offset, bodyLength, "body length equals the bytes written before the failure")
        assertTrue(bodyLength < scenario.size, "post-commit failure must truncate the body")

        assertTrue(trackingInput.closed, "the source InputStream must be released on a mid-stream failure")
    }

    private fun eventInput(): InputStream = ByteArrayInputStream(ByteArray(0))

    private fun committedPrefix(size: Long): ByteArray {
        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to size.toString(),
            ),
        )
        return Json.encodeToString(metadata).toByteArray(Charsets.UTF_8) + ByteArray(DELIMITER_LEN)
    }

    enum class Kind { REQUEST_ERROR, HEAD_NOT_FOUND, HEAD_FAILURE, POST_COMMIT }

    data class Scenario(
        val kind: Kind,
        val expectedStatus: Int,
        val size: Long = 0L,
        val offset: Long = 0L,
    )

    companion object {
        private fun statusOf(bytes: ByteArray): Int {
            val delimiterStart = indexOfDelimiter(bytes)
            require(delimiterStart >= 0) { "response is missing the 8 null-byte delimiter" }
            val metadataJson = bytes.copyOfRange(0, delimiterStart).decodeToString()
            return Json.decodeFromString<ResponseMetadata>(metadataJson).statusCode
        }

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
            Arguments.of("pre-commit request error -> 400", Scenario(Kind.REQUEST_ERROR, 400)),
            Arguments.of("pre-commit head NotFound -> 404", Scenario(Kind.HEAD_NOT_FOUND, 404)),
            Arguments.of("pre-commit head Failure -> 502", Scenario(Kind.HEAD_FAILURE, 502)),
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

    private class CapturingOutputStream : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        override fun write(b: Int) = buffer.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

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
