package nl.vintik.lambda.streaming

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectResponse
import aws.sdk.kotlin.services.s3.model.HeadObjectResponse
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromInputStream
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Unit tests for [S3Source] (Req 3.1, 3.2, 3.4, 5.7).
 *
 * The Kotlin AWS SDK [S3Client] is injected as a relaxed MockK so the two streaming
 * concerns can be exercised without a real S3 endpoint:
 *
 * - [S3Source.head] maps the `headObject` outcome to a [HeadResult]: a not-found error
 *   to [HeadResult.NotFound] (Req 3.2), any other failure or the 10 s timeout to
 *   [HeadResult.Failure] (Req 3.4), and success to [HeadResult.Exists] carrying the
 *   content length (Req 3.1).
 * - [S3Source.streamBody] consumes the `getObject` body `InputStream` inside a `.use { }`
 *   scope; on a mid-copy read failure the source stream is released and the error
 *   propagates (Req 5.7).
 *
 * Mocking approach for the streaming `getObject` DSL: the block-based
 * `getObject(request) { response -> ... }` is a member of [S3Client] (streaming
 * responses are scoped to a block), so it is stubbed with `coEvery` + `coAnswers`,
 * invoking the captured block with a [GetObjectResponse] whose `body` is a real
 * [ByteStream] built from a controlled [InputStream]. This drives the production
 * `body.toInputStream().use { copy(...) }` path end to end without a live S3 call.
 */
class S3SourceTest {

    private val client: S3Client = mockk(relaxed = true)
    private val source = S3Source(bucket = "test-bucket", client = client)

    @AfterEach
    fun tearDown() {
        clearMocks(client)
    }

    /**
     * An [InputStream] that delivers one partial chunk and then fails, recording whether
     * it was closed so the test can assert the source stream is released (Req 5.7).
     */
    private class ThrowingInputStream : InputStream() {
        @Volatile
        var closed = false
            private set
        private var reads = 0

        override fun read(): Int {
            if (reads++ >= 1) throw IOException("simulated mid-read failure")
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            if (reads == 1) {
                val n = minOf(len, 512)
                for (i in 0 until n) b[off + i] = 1
                return n
            }
            throw IOException("simulated mid-read failure")
        }

        override fun close() {
            closed = true
        }
    }

    // --- head: success -> Exists(size) (Req 3.1) ---

    @Test
    fun `Given an existing object When head is called Then it returns Exists with the content length`() = runTest {
        coEvery { client.headObject(any()) } returns HeadObjectResponse { contentLength = 15_728_640L }

        val result = source.head(FileRequest("report.bin"))

        val exists = assertInstanceOf(HeadResult.Exists::class.java, result)
        assertEquals(15_728_640L, exists.size)
    }

    @Test
    fun `Given an existing object with no declared content length When head is called Then the size defaults to zero`() = runTest {
        coEvery { client.headObject(any()) } returns HeadObjectResponse { }

        val result = source.head(FileRequest("empty.bin"))

        val exists = assertInstanceOf(HeadResult.Exists::class.java, result)
        assertEquals(0L, exists.size)
    }

    // --- head: not-found -> NotFound (Req 3.2) ---

    @Test
    fun `Given a missing object When head is called Then it returns NotFound`() = runTest {
        coEvery { client.headObject(any()) } throws NotFound { }

        val result = source.head(FileRequest("missing.bin"))

        assertEquals(HeadResult.NotFound, result)
    }

    // --- head: non-missing error -> Failure (Req 3.4) ---

    @Test
    fun `Given a non-missing S3 error When head is called Then it returns Failure carrying the cause`() = runTest {
        coEvery { client.headObject(any()) } throws RuntimeException("S3 is unavailable")

        val result = source.head(FileRequest("report.bin"))

        // The coroutine machinery may recover/copy the exception across suspension
        // boundaries, so assert on type and message rather than instance identity.
        val failure = assertInstanceOf(HeadResult.Failure::class.java, result)
        val recovered = assertInstanceOf(RuntimeException::class.java, failure.cause)
        assertEquals("S3 is unavailable", recovered.message)
    }

    // --- head: timeout -> Failure (Req 3.4) ---

    @Test
    fun `Given a head that times out When head is called Then it returns Failure with a timeout cause`() = runTest {
        // Simulate what happens when withTimeout fires: the S3 call throws a
        // CancellationException that is specifically a TimeoutCancellationException.
        // Since the constructor is internal, we trigger it via kotlinx.coroutines.withTimeout.
        coEvery { client.headObject(any()) } coAnswers {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(100L) }
            @Suppress("UNREACHABLE_CODE")
            HeadObjectResponse { contentLength = 1L }
        }

        val result = source.head(FileRequest("slow.bin"))

        val failure = assertInstanceOf(HeadResult.Failure::class.java, result)
        assertInstanceOf(TimeoutCancellationException::class.java, failure.cause)
    }

    // --- streamBody: success copies the body bytes (Req 5.1) ---

    @Test
    fun `Given an object body When streamBody is called Then it copies bytes to the sink and returns the count`() = runTest {
        val payload = ByteArray(2048) { it.toByte() }
        coEvery {
            client.getObject(any<GetObjectRequest>(), any<suspend (GetObjectResponse) -> Long>())
        } coAnswers {
            val block = secondArg<suspend (GetObjectResponse) -> Long>()
            block(GetObjectResponse { body = ByteStream.fromBytes(payload) })
        }
        val sink = ByteArrayOutputStream()
        var flushes = 0

        val total = source.streamBody(FileRequest("report.bin"), sink) { flushes++ }

        assertEquals(payload.size.toLong(), total)
        assertArrayEquals(payload, sink.toByteArray())
        assertTrue(flushes >= 1, "the sink must be flushed at least once for progressive delivery")
    }

    // --- streamBody: mid-copy failure releases the stream and propagates (Req 5.7) ---

    @Test
    fun `Given a body whose stream fails mid-copy When streamBody is called Then the source stream is released and the error propagates`() = runTest {
        val throwing = ThrowingInputStream()
        coEvery {
            client.getObject(any<GetObjectRequest>(), any<suspend (GetObjectResponse) -> Long>())
        } coAnswers {
            val block = secondArg<suspend (GetObjectResponse) -> Long>()
            block(GetObjectResponse { body = ByteStream.fromInputStream(throwing) })
        }
        val sink = ByteArrayOutputStream()

        val error = runCatching { source.streamBody(FileRequest("report.bin"), sink) { } }.exceptionOrNull()

        assertNotNull(error, "a mid-copy read failure must propagate")
        assertTrue(throwing.closed, "the source S3 input stream must be released on failure")
    }
}
