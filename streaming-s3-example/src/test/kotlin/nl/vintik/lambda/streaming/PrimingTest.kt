package nl.vintik.lambda.streaming

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.crac.Context
import org.crac.Resource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the CRaC [Priming] warm-up hook (Req 7.2, 7.3).
 *
 * Collaborators are injected as factory lambdas, so each is held as a relaxed MockK and
 * the factory is wrapped in a counter to assert the single-pass contract:
 * [Priming.beforeCheckpoint] touches each critical path — S3 client initialization, one
 * [StreamHandler] invocation, and metadata serialization — exactly once, in a single pass
 * (Req 7.2). A throwing primed path is NOT swallowed: the failure propagates so snapshot
 * creation fails (Req 7.3).
 *
 * Constructing [Priming] registers it with the global CRaC context, which is harmless in a
 * test process.
 */
class PrimingTest {

    private val s3Source: S3Source = mockk(relaxed = true)
    private val handler: StreamHandler<FileRequest> = mockk(relaxed = true)
    private val responseWriter: ResponseWriter = mockk(relaxed = true)

    @AfterEach
    fun tearDown() {
        clearMocks(s3Source, handler, responseWriter)
    }

    // --- single pass touches each critical path exactly once (Req 7.2) ---

    @Test
    fun `Given a Priming hook When beforeCheckpoint runs Then it touches S3 init handler and metadata exactly once in a single pass`() {
        val s3Calls = AtomicInteger()
        val handlerCalls = AtomicInteger()
        val writerCalls = AtomicInteger()

        val priming = Priming(
            s3SourceFactory = { s3Calls.incrementAndGet(); s3Source },
            handlerFactory = { handlerCalls.incrementAndGet(); handler },
            responseWriterFactory = { writerCalls.incrementAndGet(); responseWriter },
        )

        priming.beforeCheckpoint(null)

        // S3 client initialization exercised exactly once.
        assertEquals(1, s3Calls.get(), "S3 source factory must run exactly once")
        // Exactly one handler invocation against the primed request.
        assertEquals(1, handlerCalls.get(), "handler factory must run exactly once")
        verify(exactly = 1) { handler.handleRequest(any<InputStream>(), any<OutputStream>(), any()) }
        // Metadata serialization exercised exactly once.
        assertEquals(1, writerCalls.get(), "response writer factory must run exactly once")
        verify(exactly = 1) { responseWriter.writeMetadata(any<OutputStream>(), any()) }
    }

    // --- a throwing primed path propagates, never swallowed (Req 7.3) ---

    @Test
    fun `Given a handler that throws When beforeCheckpoint runs Then the failure propagates`() {
        val boom = RuntimeException("primed handler failure")
        every { handler.handleRequest(any(), any(), any()) } throws boom

        val priming = Priming(
            s3SourceFactory = { s3Source },
            handlerFactory = { handler },
            responseWriterFactory = { responseWriter },
        )

        val error = runCatching { priming.beforeCheckpoint(null) }.exceptionOrNull()

        assertNotNull(error, "a throwing primed path must propagate, not be swallowed")
        assertSame(boom, error)
        // The failure occurred before metadata serialization, so the snapshot fails fast.
        verify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
    }

    @Test
    fun `Given an S3 init that throws When beforeCheckpoint runs Then the failure propagates before the handler runs`() {
        val boom = IllegalStateException("primed S3 init failure")

        val priming = Priming(
            s3SourceFactory = { throw boom },
            handlerFactory = { handler },
            responseWriterFactory = { responseWriter },
        )

        val error = runCatching { priming.beforeCheckpoint(null) }.exceptionOrNull()

        assertNotNull(error, "a throwing S3 init path must propagate, not be swallowed")
        assertSame(boom, error)
        verify(exactly = 0) { handler.handleRequest(any(), any(), any()) }
        verify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
    }

    // --- afterRestore is a no-op accepting a relaxed CRaC context (Req 7.2) ---

    @Test
    fun `Given a Priming hook When afterRestore runs Then it completes without touching the primed paths`() {
        val context: Context<Resource> = mockk(relaxed = true)
        val priming = Priming(
            s3SourceFactory = { s3Source },
            handlerFactory = { handler },
            responseWriterFactory = { responseWriter },
        )

        priming.afterRestore(context)

        verify(exactly = 0) { handler.handleRequest(any(), any(), any()) }
        verify(exactly = 0) { responseWriter.writeMetadata(any(), any()) }
    }
}
