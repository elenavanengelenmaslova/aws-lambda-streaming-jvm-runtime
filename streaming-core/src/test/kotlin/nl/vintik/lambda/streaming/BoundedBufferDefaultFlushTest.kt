package nl.vintik.lambda.streaming

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Unit tests for the two-argument [copy] overload, whose `flush` argument defaults to flushing
 * the sink itself.
 *
 * Every real caller passed `{ sink.flush() }` explicitly, so the default removes a parameter that
 * carried no decision. These tests confirm the default actually reaches the sink — a default that
 * silently did nothing would look identical at the call site but drop progressive delivery.
 */
class BoundedBufferDefaultFlushTest {

    /** An [OutputStream] that records the bytes written and counts flushes. */
    private class FlushCountingOutputStream : OutputStream() {
        val written = ByteArrayOutputStream()
        var flushCount = 0
            private set

        override fun write(b: Int) {
            written.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.write(b, off, len)
        }

        override fun flush() {
            flushCount++
        }
    }

    @Test
    fun `Given no flush argument When copy is called Then the sink itself is flushed and the bytes are identical`() {
        // Given — a payload well under BUFFER_SIZE, so it is read in a single chunk.
        val source = "progressive delivery".toByteArray(Charsets.UTF_8)
        val sink = FlushCountingOutputStream()

        // When — the two-argument form, relying on the default flush.
        val total = copy(ByteArrayInputStream(source), sink)

        // Then
        assertEquals(source.size.toLong(), total)
        assertArrayEquals(source, sink.written.toByteArray())
        // One flush after the single chunk, one after the loop ends.
        assertEquals(2, sink.flushCount)
    }

    @Test
    fun `Given an empty source When copy is called Then nothing is written and the final flush still happens`() {
        // Given
        val sink = FlushCountingOutputStream()

        // When
        val total = copy(ByteArrayInputStream(ByteArray(0)), sink)

        // Then — no chunk flush, but the trailing flush still runs so the response is finalised.
        assertEquals(0L, total)
        assertEquals(0, sink.written.size())
        assertEquals(1, sink.flushCount)
    }

    @Test
    fun `Given a payload spanning several buffers When copy is called Then a flush follows every chunk`() {
        // Given — two full buffers plus a partial third, so chunking is actually exercised.
        val source = ByteArray(BUFFER_SIZE * 2 + 512) { (it % 251).toByte() }
        val sink = FlushCountingOutputStream()

        // When
        val total = copy(ByteArrayInputStream(source), sink)

        // Then
        assertEquals(source.size.toLong(), total)
        assertArrayEquals(source, sink.written.toByteArray())
        // Three chunk flushes plus the trailing one.
        assertEquals(4, sink.flushCount)
    }
}
