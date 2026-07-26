package nl.vintik.lambda.streaming

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.stream.Stream
import kotlin.random.Random

/**
 * Property 2: Streaming output is byte-identical to the source.
 *
 * For all source byte sequences `b` — across diverse sizes including 0 bytes,
 * sub-buffer (< 1 MB), exactly [BUFFER_SIZE] (1,048,576), multi-buffer multiples,
 * and non-aligned sizes with a partial final chunk — the bytes written to the sink
 * by [copy] are byte-identical to `b`: same bytes, same order, and same total count.
 * The harness also asserts the stream is flushed at least once per written chunk plus
 * a final flush, so progressive delivery is observable.
 *
 * Validates: Requirements 5.1, 5.4, 5.5, 5.6
 */
class BoundedBufferPropertyTest {

    /**
     * Sink that records every byte handed to [copy] so the captured bytes can be
     * compared to the source. The flush counter is intentionally NOT this stream's
     * own `flush()`: [copy] takes a separate `flush` lambda, so progressive delivery
     * is observed through that lambda (see the test below).
     */
    private class CapturingOutputStream : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = buffer.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

        fun captured(): ByteArray = buffer.toByteArray()
    }

    @ParameterizedTest(name = "size={0} bytes")
    @MethodSource("sizeCases")
    @Tag("Feature: s3-file-streaming-endpoint, Property 2: streamed bytes are byte-identical to the source")
    fun `Given a source of a given size When copied through the bounded buffer Then bytes order and count are identical and flush is progressive`(
        size: Int,
        seed: Long,
    ) {
        // Given: a deterministic random payload of the requested size, an in-memory source,
        // a capturing sink, and a flush lambda that increments a counter.
        val source = ByteArray(size).also { Random(seed).nextBytes(it) }
        val sink = CapturingOutputStream()
        var flushCount = 0

        // When
        val total = copy(ByteArrayInputStream(source), sink) { flushCount++ }

        // Then: byte-identical content, order, and count (Req 5.1, 5.6).
        val captured = sink.captured()
        assertArrayEquals(source, captured, "sink bytes must be byte-identical to the source")
        assertEquals(source.size.toLong(), total, "returned count must equal the source size")
        assertEquals(source.size, captured.size, "captured byte count must equal the source size")

        // And: flushed at least once per written chunk plus a final flush (Req 5.4, 5.5).
        // A 0-byte source writes no chunks but still gets the final flush -> at least 1.
        val expectedChunks = if (size == 0) 0 else (size + BUFFER_SIZE - 1) / BUFFER_SIZE
        assertTrue(
            flushCount >= expectedChunks + 1,
            "flush must be invoked at least once per chunk ($expectedChunks) plus a final flush; was $flushCount",
        )
    }

    companion object {
        /**
         * 10–20 diverse size cases: 0, sub-buffer, exactly [BUFFER_SIZE], buffer multiples,
         * and non-aligned sizes with a partial final chunk, plus a few seeded random sizes.
         */
        @JvmStatic
        fun sizeCases(): Stream<Arguments> {
            val boundary = listOf(
                0,                       // empty
                1,                       // single byte
                1024,                    // small sub-buffer
                BUFFER_SIZE - 1,         // just under one buffer
                BUFFER_SIZE,             // exactly one buffer (1,048,576)
                BUFFER_SIZE + 1,         // one buffer + partial final chunk
                2 * BUFFER_SIZE,         // exact multiple
                2 * BUFFER_SIZE + 17,    // multiple + non-aligned tail
                3 * BUFFER_SIZE,         // larger exact multiple
                3 * BUFFER_SIZE - 1,     // just under a multiple
                5 * BUFFER_SIZE + 12345, // large non-aligned
            )
            // A few reproducible random sizes spanning sub-buffer to multi-buffer.
            val randomSizes = Random(0xBEEF).let { rng ->
                List(5) { rng.nextInt(0, 4 * BUFFER_SIZE + 1) }
            }
            return (boundary + randomSizes)
                .mapIndexed { index, size -> Arguments.of(size, index.toLong() * 31 + 7) }
                .stream()
        }
    }
}
