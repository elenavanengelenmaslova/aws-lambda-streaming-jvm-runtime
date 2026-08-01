package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import nl.vintik.streaming.java.HeadResult.Exists;
import nl.vintik.streaming.java.HeadResult.Failure;
import nl.vintik.streaming.java.HeadResult.NotFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Unit tests for {@link S3Source} (AWS SDK for Java v2), driven with a Mockito
 * {@link S3Client} so behaviour is exercised without touching AWS.
 *
 * <p>Two concerns are covered:
 * <ul>
 *   <li>{@code head} maps the SDK outcomes to the sealed {@link HeadResult}: a missing object
 *       ({@link NoSuchKeyException}) &rarr; {@link NotFound} (Req 4.2); an
 *       {@link ApiCallTimeoutException} or any other failure &rarr; {@link Failure} (Req 4.4);
 *       a successful {@code headObject} &rarr; {@link Exists} carrying the reported content length
 *       for {@code Content-Length} (Req 4.1, 4.3).</li>
 *   <li>{@code streamBody} copies through the library's bounded buffer and, being wrapped in
 *       try-with-resources, releases the source {@link ResponseInputStream} on both normal
 *       completion and a mid-copy failure (source or sink throwing), propagating the failure
 *       (Req 6.6).</li>
 * </ul>
 *
 * <p>{@code head} is invoked via the {@code Consumer<HeadObjectRequest.Builder>} overload in
 * {@link S3Source} (a lambda), so the mock is stubbed on {@code headObject(any(Consumer.class))}.
 * {@code streamBody} calls {@code getObject(GetObjectRequest)} which returns a real
 * {@link ResponseInputStream} wrapping an in-memory, close-tracking stream so releasing the
 * stream can be asserted.
 *
 * <p>Requirements: 4.1, 4.2, 4.4, 6.6
 */
class S3SourceTest {

    private static final String BUCKET = "test-bucket";

    private S3Client client;
    private S3Source source;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        source = new S3Source(client, BUCKET);
    }

    // ---- head: existence / size mapping -----------------------------------------------------

    @Test
    @DisplayName("Given headObject reports a content length When head Then Exists carries that size")
    @SuppressWarnings("unchecked")
    void headSuccessMapsToExistsWithReportedSize() {
        long size = 15L * 1024 * 1024; // ~15 MB Test_Object
        HeadObjectResponse response = HeadObjectResponse.builder().contentLength(size).build();
        when(client.headObject(any(Consumer.class))).thenReturn(response);

        HeadResult result = source.head(new FileRequest("report.bin"));

        Exists exists = assertInstanceOf(Exists.class, result);
        assertEquals(size, exists.size(), "Exists must carry the reported content length");
        verify(client).headObject(any(Consumer.class));
        // Existence/size is confirmed without ever opening the body (Req 4.1 before any metadata).
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    @DisplayName("Given headObject reports no content length When head Then Exists size is zero")
    @SuppressWarnings("unchecked")
    void headSuccessWithNullContentLengthMapsToExistsZero() {
        // A response without an explicit contentLength must not blow up; it maps to size 0.
        HeadObjectResponse response = HeadObjectResponse.builder().build();
        when(client.headObject(any(Consumer.class))).thenReturn(response);

        HeadResult result = source.head(new FileRequest("empty.bin"));

        Exists exists = assertInstanceOf(Exists.class, result);
        assertEquals(0L, exists.size());
    }

    @Test
    @DisplayName("Given headObject throws NoSuchKeyException When head Then NotFound")
    @SuppressWarnings("unchecked")
    void headMissingObjectMapsToNotFound() {
        when(client.headObject(any(Consumer.class))).thenThrow(NoSuchKeyException.builder().build());

        HeadResult result = source.head(new FileRequest("missing.bin"));

        assertInstanceOf(NotFound.class, result);
    }

    @Test
    @DisplayName("Given headObject throws S3Exception with status 403 When head Then NotFound")
    @SuppressWarnings("unchecked")
    void headForbiddenOnMissingObjectMapsToNotFound() {
        // With s3:GetObject-only (no s3:ListBucket), S3 returns 403 for a missing key.
        S3Exception forbidden = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .message("Forbidden")
                .build();
        when(client.headObject(any(Consumer.class))).thenThrow(forbidden);

        HeadResult result = source.head(new FileRequest("missing-no-list-permission.bin"));

        assertInstanceOf(NotFound.class, result);
    }

    @Test
    @DisplayName("Given headObject times out When head Then Failure carries the timeout cause")
    @SuppressWarnings("unchecked")
    void headTimeoutMapsToFailure() {
        ApiCallTimeoutException timeout = ApiCallTimeoutException.create(10_000L);
        when(client.headObject(any(Consumer.class))).thenThrow(timeout);

        HeadResult result = source.head(new FileRequest("slow.bin"));

        Failure failure = assertInstanceOf(Failure.class, result);
        assertSame(timeout, failure.cause(), "the timeout must be preserved as the failure cause");
    }

    @Test
    @DisplayName("Given headObject fails with another exception When head Then Failure carries the cause")
    @SuppressWarnings("unchecked")
    void headOtherExceptionMapsToFailure() {
        S3Exception boom = (S3Exception) S3Exception.builder().message("internal error").statusCode(500).build();
        when(client.headObject(any(Consumer.class))).thenThrow(boom);

        HeadResult result = source.head(new FileRequest("boom.bin"));

        Failure failure = assertInstanceOf(Failure.class, result);
        assertSame(boom, failure.cause(), "the underlying failure must be preserved as the cause");
    }

    // ---- streamBody: bounded copy + stream release ------------------------------------------

    @Test
    @DisplayName("Given a readable object When streamBody Then bytes are copied, count returned, source released")
    void streamBodyCopiesBytesReturnsCountAndReleasesStream() throws IOException {
        byte[] payload = "hello streaming world".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream sourceStream = new CloseTrackingInputStream(new ByteArrayInputStream(payload));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(sourceStream));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        long copied = source.streamBody(new FileRequest("hello.txt"), sink);

        assertEquals(payload.length, copied, "the returned count must equal the number of bytes copied");
        assertArrayEquals(payload, sink.toByteArray(), "the sink must receive the exact source bytes");
        assertTrue(sourceStream.isClosed(), "the source ResponseInputStream must be released after a normal copy");
    }

    @Test
    @DisplayName("Given the source throws mid-copy When streamBody Then it propagates and releases the source stream")
    void streamBodyReleasesStreamAndPropagatesOnSourceFailure() {
        // Yields one chunk, then fails on the next read — a mid-copy source failure.
        CloseTrackingInputStream sourceStream =
                new CloseTrackingInputStream(new FailingAfterFirstReadInputStream("first-chunk".getBytes(StandardCharsets.UTF_8)));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(sourceStream));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        IOException thrown = assertThrows(
                IOException.class,
                () -> source.streamBody(new FileRequest("flaky.bin"), sink));

        assertEquals("simulated mid-copy read failure", thrown.getMessage());
        assertTrue(sourceStream.isClosed(), "try-with-resources must release the source stream on a mid-copy failure");
    }

    @Test
    @DisplayName("Given the sink throws mid-copy When streamBody Then it propagates and releases the source stream")
    void streamBodyReleasesStreamAndPropagatesOnSinkFailure() {
        byte[] payload = "some body bytes".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream sourceStream = new CloseTrackingInputStream(new ByteArrayInputStream(payload));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream(sourceStream));

        OutputStream failingSink = new FailingOutputStream();
        IOException thrown = assertThrows(
                IOException.class,
                () -> source.streamBody(new FileRequest("flaky-sink.bin"), failingSink));

        assertEquals("simulated sink write failure", thrown.getMessage());
        assertTrue(sourceStream.isClosed(), "try-with-resources must release the source stream when the sink fails");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Wraps a delegate stream in a real {@link ResponseInputStream} the way the SDK returns it. */
    private static ResponseInputStream<GetObjectResponse> responseStream(InputStream delegate) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(), delegate);
    }

    /** In-memory stream that records whether {@link #close()} was called (stream-release check). */
    private static final class CloseTrackingInputStream extends FilterInputStream {
        private boolean closed = false;

        CloseTrackingInputStream(InputStream in) {
            super(in);
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** Returns a single chunk on the first bulk read, then fails — simulates a mid-copy source error. */
    private static final class FailingAfterFirstReadInputStream extends InputStream {
        private final byte[] firstChunk;
        private boolean firstReadDone = false;

        FailingAfterFirstReadInputStream(byte[] firstChunk) {
            this.firstChunk = firstChunk;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException("bulk read only");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!firstReadDone) {
                firstReadDone = true;
                int n = Math.min(len, firstChunk.length);
                System.arraycopy(firstChunk, 0, b, off, n);
                return n;
            }
            throw new IOException("simulated mid-copy read failure");
        }
    }

    /** Sink that always fails on write — simulates a mid-copy sink error. */
    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("simulated sink write failure");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("simulated sink write failure");
        }
    }
}
