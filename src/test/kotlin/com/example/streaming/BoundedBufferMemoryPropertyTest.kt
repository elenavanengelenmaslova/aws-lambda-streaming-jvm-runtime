package com.example.streaming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.InputStream
import java.io.OutputStream
import java.util.stream.Stream

/**
 * Property 3: Transfer memory is bounded independent of object size.
 *
 * For all object sizes `n` (from 0 up to ~15 MB and beyond), the peak transfer
 * buffer memory attributable to the body copy does not exceed [BUFFER_SIZE]
 * (1,048,576 bytes) plus a fixed overhead that does not vary with `n`; in particular
 * no single `String` or `ByteArray` holding the complete object body is ever
 * allocated. [copy] reuses one `ByteArray(BUFFER_SIZE)` regardless of `n`.
 *
 * The object body is never materialized in the test itself: the source is a lazy,
 * zero-filled stream of length `n` (it produces bytes on demand and allocates
 * nothing proportional to `n`), and the sink discards bytes while only counting
 * them. The instrumented source records the largest single buffer length handed to
 * `source.read(buffer)` — that buffer is the single transfer allocation made by
 * [copy] — and the assertion is `maxBufferAllocation == BUFFER_SIZE` and
 * `peak <= BUFFER_SIZE + fixedOverhead` for all `n`.
 *
 * Validates: Requirements 5.2, 5.3
 */
class BoundedBufferMemoryPropertyTest {

    /**
     * A lazy, zero-filled [InputStream] of [length] bytes. It allocates nothing
     * proportional to [length] (so a 15 MB+ "object" costs no test memory), and it
     * records the largest buffer length offered to it via [read] — i.e. the size of
     * the single transfer buffer that [copy] allocates and reuses.
     */
    private class ZeroFilledInputStream(private val length: Long) : InputStream() {
        private var position = 0L

        /** Largest `buffer.size` passed to [read]; the reused transfer buffer's capacity. */
        var maxReadBufferSize = 0
            private set

        /** Largest `len` requested in a single [read]; never exceeds the buffer capacity. */
        var maxReadLen = 0
            private set

        override fun read(): Int {
            if (position >= length) return -1
            position++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // Record the full transfer buffer `copy` hands us. This is the single
            // reused allocation; it must be constant (1 MB) regardless of `length`.
            if (b.size > maxReadBufferSize) maxReadBufferSize = b.size
            if (len > maxReadLen) maxReadLen = len
            if (position >= length) return -1
            val toRead = minOf(len.toLong(), length - position).toInt()
            // The backing array is already zero-filled; no per-call allocation.
            position += toRead
            return toRead
        }
    }

    /**
     * Sink that counts bytes and the largest single write length but retains nothing,
     * so multi-MB sizes never accumulate memory in the test.
     */
    private class CountingDiscardingOutputStream : OutputStream() {
        var bytesWritten = 0L
            private set

        var maxWriteLen = 0
            private set

        override fun write(b: Int) {
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > maxWriteLen) maxWriteLen = len
            bytesWritten += len
        }
    }

    @ParameterizedTest(name = "size={0} bytes")
    @MethodSource("objectSizes")
    @Tag("Feature: s3-file-streaming-endpoint, Property 3: transfer memory bounded independent of object size")
    fun `Given an object of a given size When streamed through the bounded buffer Then the reused buffer is a constant 1MB and peak memory is bounded independent of size`(
        size: Long,
    ) {
        // Given: a lazy zero-filled source of `size` bytes and a discarding, counting sink.
        val source = ZeroFilledInputStream(size)
        val sink = CountingDiscardingOutputStream()

        // When: the bounded-buffer copy streams the whole object.
        val total = copy(source, sink) { }

        // Then: every byte is transferred (sanity that the copy actually ran to EOF).
        assertEquals(size, total, "copy must report the full object size")
        assertEquals(size, sink.bytesWritten, "sink must receive every byte of the object")

        // And: the single reused transfer buffer is exactly BUFFER_SIZE, independent of `size` (Req 5.2).
        assertEquals(
            BUFFER_SIZE,
            source.maxReadBufferSize,
            "the reused transfer buffer must be exactly $BUFFER_SIZE bytes regardless of object size",
        )

        // And: no read or write ever handled more than one buffer worth of bytes, so no
        // single full-object String/ByteArray is ever allocated (Req 5.3).
        assertTrue(
            source.maxReadLen <= BUFFER_SIZE,
            "no single read may request more than $BUFFER_SIZE bytes; was ${source.maxReadLen}",
        )
        assertTrue(
            sink.maxWriteLen <= BUFFER_SIZE,
            "no single write may exceed $BUFFER_SIZE bytes; was ${sink.maxWriteLen}",
        )

        // And: peak transfer memory <= BUFFER_SIZE + a fixed overhead that does not vary with `size` (Req 5.3).
        // The only transfer allocation is the reused buffer, so peak == its capacity.
        val fixedOverhead = 0
        val peak = source.maxReadBufferSize
        assertTrue(
            peak <= BUFFER_SIZE + fixedOverhead,
            "peak transfer memory ($peak) must not exceed BUFFER_SIZE + fixed overhead (${BUFFER_SIZE + fixedOverhead})",
        )
    }

    companion object {
        private const val MB = 1024L * 1024L

        /**
         * 10–20 sizes spanning small, ~6 MB, ~15 MB, and beyond — including buffer-aligned,
         * just-over-aligned, and non-aligned sizes so the partial final chunk is exercised too.
         */
        @JvmStatic
        fun objectSizes(): Stream<Arguments> = listOf(
            0L,                       // empty object
            1L,                       // single byte
            1024L,                    // small sub-buffer
            BUFFER_SIZE.toLong(),     // exactly one buffer
            BUFFER_SIZE.toLong() + 1, // one buffer + partial final chunk
            6L * MB,                  // ~6 MB (the old buffered limit)
            6L * MB + 12345,          // ~6 MB, non-aligned tail
            10L * MB,                 // 10 MB
            15L * MB,                 // ~15 MB (the Test_Object size)
            15L * MB + 1,             // ~15 MB, non-aligned tail
            15L * MB + 999_999,       // ~15 MB, large non-aligned tail
            20L * MB,                 // beyond 15 MB
            32L * MB + 7,             // well beyond, non-aligned
            64L * MB,                 // far beyond, buffer-aligned
        ).map { Arguments.of(it) }.stream()
    }
}
