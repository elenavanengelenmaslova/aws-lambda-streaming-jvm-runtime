package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.stream.Stream;
import nl.vintik.lambda.streaming.BoundedBufferKt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Property 2: Streaming output is byte-identical to the source — from Java.
 *
 * <p>For all source byte sequences {@code b} — across diverse sizes including 0 bytes, sub-buffer
 * (&lt; 1 MB), exactly {@link BoundedBufferKt#BUFFER_SIZE} (1,048,576), multi-buffer multiples, and
 * non-aligned sizes with a partial final chunk — the bytes {@link S3Source#streamBody} writes to the
 * sink are byte-identical to {@code b}: same bytes, same order, and same total count. The harness
 * also asserts the sink is flushed at least once per written chunk plus a final flush, so
 * progressive delivery is observable (the library's 2-arg {@code copy} defaults its flush to
 * {@code sink.flush()}, so the flush-counting sink observes exactly those flushes).
 *
 * <p><b>Source/sink choice.</b> The test drives the real production path — {@code S3Source.streamBody}
 * backed by a Mockito {@link S3Client} whose {@code getObject} returns a
 * {@link ResponseInputStream}{@code <}{@link GetObjectResponse}{@code >} over an in-memory
 * {@link ByteArrayInputStream} — rather than calling the library {@code copy} directly. This is the
 * stronger assertion for Property 2 as the design states it ("the bytes written to the sink by
 * {@code S3Source.streamBody}, driving the library's {@code copy}"): it proves the Java S3 source,
 * through the SDK v2 {@code ResponseInputStream} and try-with-resources, hands bytes to the library
 * {@code copy} unchanged and flushes per chunk. The sink is a {@link FlushCountingOutputStream} that
 * both captures every byte and counts {@code flush()} calls. Sizes are generated in-memory (largest
 * case ~5 MB) so no fixtures are stored under {@code src/test/resources/test-data/}. Random-size
 * cases use a seeded {@link Random} so any failure reproduces deterministically.
 *
 * <p>The property identifier is carried in {@code @Tag} using the exact string from the design's
 * Correctness Properties, matching the {@code @Tag} convention the {@code :streaming-core} property
 * tests and {@link MetadataRoundTripPropertyTest} already use.
 *
 * <p>Validates: Requirements 1.4, 6.1, 6.4, 6.5
 */
@Tag("Feature: java-s3-file-streaming-endpoint, Property 2: streamed bytes are byte-identical to the source")
class StreamingByteIdenticalPropertyTest {

    /**
     * Sink that records every byte handed to it by the library {@code copy} (so the captured bytes
     * can be compared to the source) and counts {@code flush()} invocations. Because the 2-arg
     * {@code copy} flushes the sink itself after each chunk plus once at the end, this counter
     * observes progressive delivery directly through the production flush path.
     */
    private static final class FlushCountingOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int flushCount = 0;

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() {
            flushCount++;
        }

        byte[] captured() {
            return buffer.toByteArray();
        }

        int flushCount() {
            return flushCount;
        }
    }

    @ParameterizedTest(name = "[{index}] size={0} bytes")
    @MethodSource("sizeCases")
    @DisplayName("Given an S3 object of a given size When streamed via S3Source.streamBody Then bytes order and count are identical and flush is progressive")
    void streamBodyWritesByteIdenticalBytesAndFlushesProgressively(int size, long seed)
            throws IOException {
        // Given: a deterministic random payload of the requested size, exposed to S3Source as a
        // ResponseInputStream over an in-memory ByteArrayInputStream (the shape client.getObject
        // returns in production), and a flush-counting capturing sink.
        byte[] source = new byte[size];
        new Random(seed).nextBytes(source);

        S3Client client = mock(S3Client.class);
        ResponseInputStream<GetObjectResponse> objectStream =
                new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) size).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(source)));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(objectStream);

        S3Source s3Source = new S3Source(client, "test-bucket");
        FlushCountingOutputStream sink = new FlushCountingOutputStream();

        // When: the object body is streamed through S3Source -> library copy -> sink.
        long total = s3Source.streamBody(new FileRequest("object.bin"), sink);

        // Then: byte-identical content, order, and count (Req 1.4, 6.5).
        byte[] captured = sink.captured();
        assertArrayEquals(source, captured, "sink bytes must be byte-identical to the source object");
        assertEquals((long) size, total, "returned count must equal the source object size");
        assertEquals(size, captured.length, "captured byte count must equal the source object size");

        // And: flushed at least once per written chunk plus a final flush (Req 6.1, 6.4).
        // A 0-byte object writes no chunks but still gets the final flush -> at least 1.
        int bufferSize = BoundedBufferKt.BUFFER_SIZE;
        int expectedChunks = size == 0 ? 0 : (size + bufferSize - 1) / bufferSize;
        assertTrue(
                sink.flushCount() >= expectedChunks + 1,
                "flush must be invoked at least once per chunk ("
                        + expectedChunks
                        + ") plus a final flush; was "
                        + sink.flushCount());
    }

    /**
     * 11 boundary sizes (0, single byte, small sub-buffer, just under/at/just over one buffer,
     * exact and non-aligned multi-buffer multiples, a large non-aligned size) plus 5 reproducible
     * seeded random sizes spanning sub-buffer to multi-buffer — 16 diverse cases in the 10–20 range.
     * Each case carries a distinct seed so the random payload (and any failure) is deterministic.
     */
    static Stream<Arguments> sizeCases() {
        int b = BoundedBufferKt.BUFFER_SIZE;
        int[] boundary = {
            0, // empty
            1, // single byte
            1024, // small sub-buffer
            b - 1, // just under one buffer
            b, // exactly one buffer (1,048,576)
            b + 1, // one buffer + partial final chunk
            2 * b, // exact multiple
            2 * b + 17, // multiple + non-aligned tail
            3 * b, // larger exact multiple
            3 * b - 1, // just under a multiple
            5 * b + 12345 // large non-aligned
        };

        Stream.Builder<Arguments> cases = Stream.builder();
        for (int i = 0; i < boundary.length; i++) {
            cases.add(Arguments.of(boundary[i], (long) i * 31 + 7));
        }

        // A few reproducible random sizes spanning sub-buffer to multi-buffer.
        Random sizeRng = new Random(0xBEEFL);
        for (int i = 0; i < 5; i++) {
            int size = sizeRng.nextInt(4 * b + 1);
            cases.add(Arguments.of(size, (long) (boundary.length + i) * 31 + 7));
        }

        return cases.build();
    }
}
