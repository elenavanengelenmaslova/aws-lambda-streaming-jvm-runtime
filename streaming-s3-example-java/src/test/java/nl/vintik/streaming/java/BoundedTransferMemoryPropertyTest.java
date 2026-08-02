package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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
 * Property 3: Transfer memory is bounded independent of object size — from Java.
 *
 * <p>For all object sizes {@code n} — from a few bytes, through the legacy ~6 MB buffered limit and
 * the ~15 MB {@code Test_Object}, up to sizes well beyond (~64 MB) — the transfer memory the copy
 * path uses does not grow with {@code n}: the library reads through a single reused transfer buffer
 * of the fixed {@link BoundedBufferKt#BUFFER_SIZE} (1,048,576 bytes), no chunk handed to the sink
 * ever exceeds that buffer, and for objects larger than the buffer no chunk is ever sized to the
 * whole object. No single {@code String}/{@code byte[]} holding the entire body is allocated, so
 * peak transfer memory stays at the fixed buffer plus a fixed overhead independent of {@code n}
 * (Req 6.2, 6.3).
 *
 * <p><b>How boundedness is observed.</b> The test drives the real production path
 * {@code S3Source.streamBody} (backed by a Mockito {@link S3Client} whose {@code getObject} returns
 * a {@link ResponseInputStream}{@code <}{@link GetObjectResponse}{@code >}), so the assertion covers
 * S3Source + the library {@code copy} together — proving S3Source adds no full-object buffer on top
 * of the library (Req 6.3). Both the source and the sink are instrumented:
 *
 * <ul>
 *   <li>The <b>source</b> ({@link GeneratingInputStream}) produces {@code n} bytes on the fly and
 *       never allocates an {@code n}-sized array, so a ~64 MB case costs only the library's 1 MB
 *       buffer. It records the largest {@code len} it is ever asked to read into — proving the
 *       copy never requests a read sized to the whole object.
 *   <li>The <b>sink</b> ({@link CountingSink}) <em>discards</em> every byte (retains nothing) while
 *       recording, per {@code write(buffer, off, len)} call, the identity and length of the array
 *       handed to it and the maximum {@code len}. Because the library writes its own reused buffer
 *       straight to the sink ({@code sink.write(buffer, 0, read)}), the sink observes the library's
 *       actual transfer buffer directly: a single distinct instance of exactly {@code BUFFER_SIZE}
 *       bytes for every object size. Discarding bytes is what keeps the large cases cheap and stops
 *       the test itself from defeating the property by materializing the object.
 * </ul>
 *
 * <p>Because content correctness (byte-identity) is covered by Property 2, this test asserts only
 * counts, chunk sizes, and buffer reuse — it does not retain or compare body bytes.
 *
 * <p>The property identifier is carried in {@code @Tag} using the exact string from the design's
 * Correctness Properties, matching the {@code @Tag} convention the other Java property tests use.
 *
 * <p>Validates: Requirements 6.2, 6.3
 */
@Tag("Feature: java-s3-file-streaming-endpoint, Property 3: transfer memory bounded independent of object size")
class BoundedTransferMemoryPropertyTest {

    /**
     * Produces {@code size} bytes on demand without ever allocating a {@code size}-sized array, so
     * large object sizes (~64 MB) exercise the copy at the cost of only the library's transfer
     * buffer. Records the largest {@code len} the copy asks it to read, to prove no read is ever
     * requested for the whole object.
     */
    private static final class GeneratingInputStream extends InputStream {
        private final long size;
        private long produced = 0;
        private int maxReadRequestLen = 0;

        GeneratingInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (produced >= size) {
                return -1;
            }
            produced++;
            return 0x5A;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            maxReadRequestLen = Math.max(maxReadRequestLen, len);
            if (produced >= size) {
                return -1;
            }
            int n = (int) Math.min((long) len, size - produced);
            // Content is irrelevant here (Property 2 covers byte-identity); a fast intrinsic fill
            // honours the InputStream contract without a per-byte loop, keeping large cases cheap.
            Arrays.fill(b, off, off + n, (byte) 0x5A);
            produced += n;
            return n;
        }

        int maxReadRequestLen() {
            return maxReadRequestLen;
        }
    }

    /**
     * Sink that discards every byte (retains nothing) while recording the library's transfer
     * buffer: the identity and length of each array written, the maximum chunk length, and the
     * running total. Because the library writes its single reused buffer straight through
     * ({@code sink.write(buffer, 0, read)}), these observations describe the library's actual
     * buffer, not a copy the test made.
     */
    private static final class CountingSink extends OutputStream {
        private long total = 0;
        private int maxWriteLen = 0;
        private int transferBufferLength = -1;
        private final Set<Integer> bufferInstances = new HashSet<>();

        @Override
        public void write(int b) {
            // The library copy never uses the single-byte path, but keep the count honest.
            total++;
            maxWriteLen = Math.max(maxWriteLen, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            maxWriteLen = Math.max(maxWriteLen, len);
            transferBufferLength = b.length;
            bufferInstances.add(System.identityHashCode(b));
            total += len; // bytes are intentionally discarded, only counted
        }

        long total() {
            return total;
        }

        int maxWriteLen() {
            return maxWriteLen;
        }

        int transferBufferLength() {
            return transferBufferLength;
        }

        int distinctBufferInstances() {
            return bufferInstances.size();
        }
    }

    @ParameterizedTest(name = "[{index}] size={0} bytes")
    @MethodSource("objectSizes")
    @DisplayName(
            "Given an S3 object of a given size When streamed via S3Source.streamBody Then the copy"
                    + " reuses one fixed BUFFER_SIZE buffer and no chunk is sized to the whole object")
    void transferMemoryStaysBoundedIndependentOfObjectSize(long size) throws IOException {
        // Given: a source that generates `size` bytes on the fly (never materialising the object),
        // exposed as the ResponseInputStream shape client.getObject returns in production, and a
        // sink that discards bytes while observing the library's transfer buffer.
        int bufferSize = BoundedBufferKt.BUFFER_SIZE;

        GeneratingInputStream generating = new GeneratingInputStream(size);
        S3Client client = mock(S3Client.class);
        ResponseInputStream<GetObjectResponse> objectStream =
                new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength(size).build(),
                        AbortableInputStream.create(generating));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(objectStream);

        S3Source s3Source = new S3Source(client, "test-bucket");
        CountingSink sink = new CountingSink();

        // When: the object body is streamed through S3Source -> library copy -> sink.
        long total = s3Source.streamBody(new FileRequest("object.bin"), sink);

        // Then: every byte flowed through, but nothing was ever asked to be read as one whole-object
        // chunk — the copy reads at most one buffer at a time (Req 6.2, 6.3).
        assertEquals(size, total, "all bytes must be copied");
        assertEquals(
                size, sink.total(), "the sink must observe every byte (independently counted)");
        assertTrue(
                generating.maxReadRequestLen() <= bufferSize,
                "a single read must never be requested for more than the fixed buffer; was "
                        + generating.maxReadRequestLen());

        // And: the library's transfer buffer — observed directly on the sink side — is a single
        // reused instance of exactly BUFFER_SIZE, identical for every object size. This is the core
        // "bounded independent of object size" proof: the buffer does not scale with `size`.
        if (size > 0) {
            assertEquals(
                    1,
                    sink.distinctBufferInstances(),
                    "the copy must reuse a single transfer buffer for all writes, not allocate per"
                            + " chunk or per object");
            assertEquals(
                    bufferSize,
                    sink.transferBufferLength(),
                    "the transfer buffer length must equal the fixed library BUFFER_SIZE regardless"
                            + " of object size");
        }

        // And: no chunk handed to the sink ever exceeds the fixed buffer, for any object size.
        assertTrue(
                sink.maxWriteLen() <= bufferSize,
                "the maximum written chunk must be <= the library BUFFER_SIZE; was "
                        + sink.maxWriteLen());

        // And: for objects larger than the buffer, no chunk is ever sized to the whole object —
        // peak transfer memory therefore cannot grow with object size (Req 6.2, 6.3).
        if (size > bufferSize) {
            assertTrue(
                    sink.maxWriteLen() < size,
                    "no single chunk may be sized to the whole object; chunk="
                            + sink.maxWriteLen()
                            + " object="
                            + size);
        }
    }

    /**
     * 16 object sizes in the 10–20 range: a few bytes and sub-buffer sizes, the buffer boundary
     * (just under / exactly / just over 1 MB), the legacy ~6 MB buffered limit, the ~15 MB
     * {@code Test_Object}, and sizes well beyond it (~20/32/50/64 MB), each with aligned and
     * non-aligned variants. The large sizes are cheap because the source generates bytes on the fly
     * and the sink discards them, so peak memory stays at the fixed 1 MB buffer for every case.
     */
    static Stream<Arguments> objectSizes() {
        long b = BoundedBufferKt.BUFFER_SIZE; // 1,048,576
        return Stream.of(
                Arguments.of(0L), // empty
                Arguments.of(1L), // single byte
                Arguments.of(1024L), // small sub-buffer
                Arguments.of(b - 1), // just under one buffer
                Arguments.of(b), // exactly one buffer
                Arguments.of(b + 1), // one buffer + partial final chunk
                Arguments.of(2 * b), // 2 MB, exact multiple
                Arguments.of(6 * b), // ~6 MB, the legacy buffered limit
                Arguments.of(6 * b + 12_345), // ~6 MB, non-aligned tail
                Arguments.of(10 * b), // ~10 MB
                Arguments.of(15 * b), // ~15 MB, the Test_Object
                Arguments.of(15 * b + 7), // ~15 MB, non-aligned tail
                Arguments.of(20 * b), // ~20 MB, beyond the limit
                Arguments.of(32 * b), // ~32 MB, well beyond
                Arguments.of(50 * b + 4_096), // ~50 MB, far beyond, non-aligned
                Arguments.of(64 * b + 999)); // ~64 MB, far beyond, non-aligned
    }
}
