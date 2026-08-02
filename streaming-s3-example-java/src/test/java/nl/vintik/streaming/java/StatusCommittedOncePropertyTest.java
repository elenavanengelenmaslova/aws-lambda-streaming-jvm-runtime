package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

/**
 * Property 5: Status is committed exactly once; failures never rewrite it — from Java.
 *
 * <p>For all failure-injection points, existence/size is confirmed <em>before</em> any metadata is
 * written, and the committed HTTP status is written at most once:
 *
 * <ul>
 *   <li><b>Pre-commit failures</b> — a parse error, a validation rejection, a {@code NotFound}, or a
 *       head {@code Failure}/timeout — produce a metadata-only error response carrying the mapped
 *       status (400/404/502) and <em>no object/file body</em>; the S3 body is never opened
 *       ({@code streamBody} is never called). A write failure that strikes <em>during</em> the
 *       metadata prelude or the 8-null-byte delimiter propagates and leaves the boundary incomplete,
 *       so the status is never committed and no body is written (Req 2.2, 2.3, 4.1, 4.2, 4.4, 5.4).</li>
 *   <li><b>Post-commit failures</b> — the source or the sink throwing after the 200 metadata and the
 *       delimiter are fully written — can only <em>truncate</em> the body: the committed status
 *       byte-prefix still decodes to the 200 prelude ({@code Content-Type: application/octet-stream},
 *       {@code Content-Length} = object size), fewer than the declared object bytes reach the sink,
 *       the S3 source stream is released, and the error propagates (Req 4.3, 5.3, 6.6, 7.3).</li>
 * </ul>
 *
 * <p><b>Wiring / injection approach.</b> The handler is driven through its package-private
 * constructor with the <em>real</em> {@link RequestParser} and {@link FileNameValidator} (so parse
 * and validation outcomes are genuine), the <em>real</em> library {@link ResponseWriter} constructed
 * exactly as production does ({@code new ResponseWriter(Json.Default,
 * ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)}, so the real wire format and commit point are
 * exercised), and a Mockito {@link S3Source} to inject each outcome:
 * <ul>
 *   <li>{@code head} is stubbed to return {@link HeadResult.NotFound}, {@link HeadResult.Failure}
 *       (generic and timeout causes, both mapping to 502), or {@link HeadResult.Exists};</li>
 *   <li>{@code streamBody} is stubbed to throw immediately, to write a partial body then throw, or
 *       to attempt a full-object write into a sink that fails partway (a mid-stream sink failure).</li>
 * </ul>
 * A write failure <em>during</em> the metadata prelude/delimiter is injected with a
 * {@link CapturingFailingOutputStream} that records every byte and throws once a chosen cumulative
 * byte offset is reached — so the bytes written before the failure can be inspected to prove the
 * status was never committed. To prove the committed status is unchanged post-commit, the bytes up
 * to the delimiter are captured and decoded, and must still decode to the 200 prelude even after
 * {@code streamBody} throws.
 *
 * <p><b>Source-stream release.</b> Because {@code S3Source} is a Mockito double here, releasing the
 * underlying {@code ResponseInputStream} on a mid-stream failure is {@code S3Source}'s own
 * responsibility and is unit-tested directly in {@link S3SourceTest} (task 6.2,
 * {@code streamBody} releases the stream on source/sink failure). At the handler level this property
 * asserts the pragmatic, observable contract: a post-commit mid-stream failure <em>propagates</em>
 * (it is not swallowed), which is what lets the runtime abort and truncate the response (Req 6.6, 7.3).
 *
 * <p>Random-free and deterministic: every case is a fixed, seeded injection point, so any failure
 * reproduces exactly. The property identifier is carried in {@code @Tag} using the exact string from
 * the design's Correctness Properties, matching the convention the other Java property tests use.
 *
 * <p>Validates: Requirements 2.2, 2.3, 4.1, 4.2, 4.3, 4.4, 5.3, 5.4, 6.6, 7.3
 */
@Tag("Feature: java-s3-file-streaming-endpoint, Property 5: status committed once, failures never rewrite it")
class StatusCommittedOncePropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Content length declared for every {@link HeadResult.Exists} case (a fixed, deterministic size). */
    private static final long OK_SIZE = 65_536L;

    /** The exact bytes the handler commits for a 200 response of {@link #OK_SIZE}: prelude + delimiter. */
    private static final byte[] COMMITTED_OK_PREFIX = committedOkPrefix();

    /** Length of just the 200 prelude (without the delimiter), used to place mid-prelude/mid-delimiter faults. */
    private static final int OK_PRELUDE_LEN = COMMITTED_OK_PREFIX.length - ResponseWriterKt.DELIMITER_LEN;

    /** Body bytes a "write partial then throw" source emits before failing (well under {@link #OK_SIZE}). */
    private static final int PARTIAL_BODY_BYTES = 64;

    /** Body offset at which the sink fails in the mid-stream-sink-failure case (well under {@link #OK_SIZE}). */
    private static final int SINK_FAIL_BODY_OFFSET = 128;

    /** What {@link StreamHandler} asks the (mock) {@link S3Source#head} to return, per case. */
    private enum HeadPlan { NOT_REACHED, NOT_FOUND, FAILURE, EXISTS }

    /** How the (mock) {@link S3Source#streamBody} behaves once the status is committed, per case. */
    private enum StreamPlan { NOT_REACHED, THROW_IMMEDIATELY, WRITE_PARTIAL_THEN_THROW, WRITE_UNTIL_SINK_FAILS }

    /** The assertion family a case belongs to. */
    private enum Kind {
        /** A clean 400/404/502 metadata-only error response; no object body; {@code streamBody} never called. */
        PRE_COMMIT_ERROR,
        /** A write failure during the metadata/delimiter; propagates, status uncommitted, no body. */
        PRE_COMMIT_WRITE_FAIL,
        /** A failure after commit; propagates, committed 200 prefix intact, body truncated. */
        POST_COMMIT_FAIL
    }

    /**
     * A single deterministic injection point.
     *
     * @param kind             which assertion family applies
     * @param headPlan         what {@code head} returns (or that it is never reached)
     * @param streamPlan       how {@code streamBody} behaves (or that it is never reached)
     * @param input            the raw API Gateway proxy event bytes fed to the handler
     * @param failAtByte       cumulative sink byte offset to fail at, or {@code -1} for a non-failing sink
     * @param expectedStatus   the mapped status for a {@link Kind#PRE_COMMIT_ERROR} case (else ignored)
     * @param headFailureCause the cause carried by a {@link HeadPlan#FAILURE} case (else {@code null})
     */
    private record Scenario(
            Kind kind,
            HeadPlan headPlan,
            StreamPlan streamPlan,
            byte[] input,
            int failAtByte,
            int expectedStatus,
            Throwable headFailureCause) {}

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenarios")
    @DisplayName(
            "Given a failure-injection point When the handler runs Then the status is committed at most"
                    + " once and failures never rewrite it")
    void statusIsCommittedOnceAndFailuresNeverRewriteIt(String label, Scenario scenario) throws IOException {
        // Real parser + validator + writer (genuine parse/validation outcomes and the real wire format);
        // only the S3 source is mocked, to inject each existence/streaming outcome.
        RequestParser parser = new RequestParser();
        FileNameValidator validator = new FileNameValidator();
        S3Source source = mock(S3Source.class);
        ResponseWriter writer = new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN);
        StreamHandler handler = new StreamHandler(parser, validator, source, writer);

        stubHead(source, scenario);
        stubStreamBody(source, scenario);

        CapturingFailingOutputStream sink = new CapturingFailingOutputStream(scenario.failAtByte());
        ByteArrayInputStream input = new ByteArrayInputStream(scenario.input());

        switch (scenario.kind()) {
            case PRE_COMMIT_ERROR -> {
                // No exception: a complete metadata-only error response is written.
                handler.handleRequest(input, sink, null);
                assertErrorResponseNoObjectBody(sink.captured(), scenario.expectedStatus());
                // The mapped status is committed without ever opening the S3 body (Req 2.2, 4.2, 4.4).
                verify(source, never()).streamBody(any(), any());
            }
            case PRE_COMMIT_WRITE_FAIL -> {
                // The write failure during the metadata/delimiter must propagate (Req 5.4).
                assertThrows(IOException.class, () -> handler.handleRequest(input, sink, null));
                // The 8-null boundary was never completed, so the status is uncommitted...
                assertTrue(
                        indexOfDelimiter(sink.captured()) < 0,
                        "the metadata/delimiter boundary must be incomplete, leaving the status uncommitted");
                // ...and no body was streamed.
                verify(source, never()).streamBody(any(), any());
            }
            case POST_COMMIT_FAIL -> {
                // The post-commit failure must propagate (so the runtime aborts/truncates) (Req 6.6, 7.3).
                assertThrows(IOException.class, () -> handler.handleRequest(input, sink, null));
                assertCommittedOkPrefixUnchangedAndTruncated(sink.captured());
            }
        }
    }

    /**
     * 18 deterministic injection points across the three families (within the 10&ndash;20 range):
     * twelve pre-commit error mappings (three parse errors, six validation rejections, a NotFound,
     * and two head failures), three pre-commit write failures (mid-200 prelude, mid-delimiter, and
     * mid-404-error prelude), and three post-commit failures (source throws immediately, source
     * writes a partial body then throws, and a sink that fails mid-body).
     */
    static Stream<Arguments> scenarios() {
        return Stream.of(
                // ---- Pre-commit error mappings: mapped status committed, no object body (Req 2.2, 2.3, 4.2, 4.4) ----
                preCommitError("parse error: malformed JSON -> 400", HeadPlan.NOT_REACHED, bytes("not json at all"), 400),
                preCommitError(
                        "parse error: truncated JSON -> 400",
                        HeadPlan.NOT_REACHED,
                        bytes("{\"pathParameters\":{\"proxy\":\"report.pdf\""),
                        400),
                preCommitError("parse error: empty input -> 400", HeadPlan.NOT_REACHED, new byte[0], 400),
                preCommitError("validation reject: empty file name -> 400", HeadPlan.NOT_REACHED, proxyEvent(""), 400),
                preCommitError(
                        "validation reject: path separator -> 400", HeadPlan.NOT_REACHED, proxyEvent("dir/file.txt"), 400),
                preCommitError("validation reject: parent dir -> 400", HeadPlan.NOT_REACHED, proxyEvent(".."), 400),
                preCommitError(
                        "validation reject: absolute path -> 400", HeadPlan.NOT_REACHED, proxyEvent("/etc/passwd"), 400),
                preCommitError(
                        "validation reject: illegal character -> 400", HeadPlan.NOT_REACHED, proxyEvent("bad*name"), 400),
                preCommitError(
                        "validation reject: too long -> 400", HeadPlan.NOT_REACHED, proxyEvent("a".repeat(1025)), 400),
                preCommitError("head NotFound -> 404", HeadPlan.NOT_FOUND, proxyEvent("missing.bin"), 404),
                headFailure("head Failure (generic) -> 502", proxyEvent("boom.bin"), new RuntimeException("head failed")),
                headFailure(
                        "head Failure (timeout) -> 502", proxyEvent("slow.bin"), ApiCallTimeoutException.create(10_000L)),

                // ---- Pre-commit write failures: propagate, status uncommitted, no body (Req 5.4) ----
                preCommitWriteFail(
                        "write fails mid-200-prelude -> propagate, uncommitted",
                        HeadPlan.EXISTS,
                        proxyEvent("report.bin"),
                        3),
                preCommitWriteFail(
                        "write fails within the 8-null delimiter -> propagate, uncommitted",
                        HeadPlan.EXISTS,
                        proxyEvent("report.bin"),
                        OK_PRELUDE_LEN + 4),
                preCommitWriteFail(
                        "write fails mid-404-error-prelude -> propagate, uncommitted",
                        HeadPlan.NOT_FOUND,
                        proxyEvent("missing.bin"),
                        3),

                // ---- Post-commit failures: committed 200 unchanged, body truncated, propagate (Req 5.3, 7.3) ----
                postCommitFail(
                        "source throws immediately after commit -> truncated to 0 body bytes",
                        StreamPlan.THROW_IMMEDIATELY,
                        -1),
                postCommitFail(
                        "source writes partial body then throws -> truncated body",
                        StreamPlan.WRITE_PARTIAL_THEN_THROW,
                        -1),
                postCommitFail(
                        "sink throws mid-body after commit -> truncated body",
                        StreamPlan.WRITE_UNTIL_SINK_FAILS,
                        COMMITTED_OK_PREFIX.length + SINK_FAIL_BODY_OFFSET));
    }

    // ---- scenario factories -----------------------------------------------------------------

    private static Arguments preCommitError(String label, HeadPlan headPlan, byte[] input, int expectedStatus) {
        return Arguments.of(
                label,
                new Scenario(Kind.PRE_COMMIT_ERROR, headPlan, StreamPlan.NOT_REACHED, input, -1, expectedStatus, null));
    }

    private static Arguments headFailure(String label, byte[] input, Throwable cause) {
        return Arguments.of(
                label,
                new Scenario(Kind.PRE_COMMIT_ERROR, HeadPlan.FAILURE, StreamPlan.NOT_REACHED, input, -1, 502, cause));
    }

    private static Arguments preCommitWriteFail(String label, HeadPlan headPlan, byte[] input, int failAtByte) {
        return Arguments.of(
                label,
                new Scenario(Kind.PRE_COMMIT_WRITE_FAIL, headPlan, StreamPlan.NOT_REACHED, input, failAtByte, 0, null));
    }

    private static Arguments postCommitFail(String label, StreamPlan streamPlan, int failAtByte) {
        return Arguments.of(
                label,
                new Scenario(
                        Kind.POST_COMMIT_FAIL, HeadPlan.EXISTS, streamPlan, proxyEvent("report.bin"), failAtByte, 200, null));
    }

    // ---- mock stubbing ----------------------------------------------------------------------

    private static void stubHead(S3Source source, Scenario scenario) {
        switch (scenario.headPlan()) {
            case NOT_REACHED -> {
                // Left unstubbed; the case verifies head is never reached, or it simply is not called.
            }
            case NOT_FOUND -> when(source.head(any())).thenReturn(new HeadResult.NotFound());
            case FAILURE -> when(source.head(any())).thenReturn(new HeadResult.Failure(scenario.headFailureCause()));
            case EXISTS -> when(source.head(any())).thenReturn(new HeadResult.Exists(OK_SIZE));
        }
    }

    private static void stubStreamBody(S3Source source, Scenario scenario) throws IOException {
        switch (scenario.streamPlan()) {
            case NOT_REACHED -> {
                // Left unstubbed; these cases verify streamBody is never called.
            }
            case THROW_IMMEDIATELY ->
                when(source.streamBody(any(), any()))
                        .thenThrow(new IOException("simulated mid-stream source failure (no body written)"));
            case WRITE_PARTIAL_THEN_THROW ->
                when(source.streamBody(any(), any()))
                        .thenAnswer(
                                invocation -> {
                                    OutputStream out = invocation.getArgument(1);
                                    out.write(new byte[PARTIAL_BODY_BYTES]);
                                    throw new IOException("simulated mid-stream source failure after partial body");
                                });
            case WRITE_UNTIL_SINK_FAILS ->
                when(source.streamBody(any(), any()))
                        .thenAnswer(
                                invocation -> {
                                    OutputStream out = invocation.getArgument(1);
                                    // Attempt the whole object; the capturing sink fails partway through.
                                    out.write(new byte[(int) OK_SIZE]);
                                    return OK_SIZE; // unreachable: the sink throws first
                                });
        }
    }

    // ---- assertions -------------------------------------------------------------------------

    /**
     * Asserts a clean pre-commit error response: the prelude decodes to {@code expectedStatus} with a
     * JSON content type, and the only bytes after the delimiter are the small {@code {"message":...}}
     * {@code ErrorBody} — never object/file body bytes.
     */
    private void assertErrorResponseNoObjectBody(byte[] captured, int expectedStatus) throws IOException {
        int delimiterStart = indexOfDelimiter(captured);
        assertTrue(delimiterStart >= 0, "an error response must write the prelude and the 8-null delimiter");

        JsonNode prelude = decodePrelude(captured, delimiterStart);
        assertEquals(
                expectedStatus, prelude.get("statusCode").asInt(), "the committed status must be the mapped error status");
        assertEquals(
                "application/json",
                prelude.get("headers").get("Content-Type").asText(),
                "a pre-commit failure is a JSON error response, not a committed body stream");

        byte[] bodyBytes =
                Arrays.copyOfRange(captured, delimiterStart + ResponseWriterKt.DELIMITER_LEN, captured.length);
        JsonNode body = MAPPER.readTree(new String(bodyBytes, StandardCharsets.UTF_8));
        assertTrue(
                body.has("message"),
                "the body must be the ErrorBody JSON message, proving no object/file body was written");
    }

    /**
     * Asserts a post-commit failure left the committed 200 prelude byte-prefix intact (status, content
     * type, and declared length all unchanged) and truncated the body to fewer than the declared object
     * size.
     */
    private void assertCommittedOkPrefixUnchangedAndTruncated(byte[] captured) throws IOException {
        int delimiterStart = indexOfDelimiter(captured);
        assertTrue(delimiterStart >= 0, "the status must have been committed: prelude + 8-null delimiter present");

        JsonNode prelude = decodePrelude(captured, delimiterStart);
        assertEquals(
                200, prelude.get("statusCode").asInt(), "the committed 200 status must be unchanged by the failure");
        JsonNode headers = prelude.get("headers");
        assertEquals(
                "application/octet-stream",
                headers.get("Content-Type").asText(),
                "the committed success prelude must remain intact");
        assertEquals(
                String.valueOf(OK_SIZE),
                headers.get("Content-Length").asText(),
                "the committed Content-Length must remain intact");

        long bodyLen = (long) captured.length - (delimiterStart + ResponseWriterKt.DELIMITER_LEN);
        assertTrue(
                bodyLen < OK_SIZE,
                "the body must be truncated (fewer bytes than the declared object size); was " + bodyLen);
    }

    private static JsonNode decodePrelude(byte[] captured, int delimiterStart) throws IOException {
        return MAPPER.readTree(new String(Arrays.copyOfRange(captured, 0, delimiterStart), StandardCharsets.UTF_8));
    }

    /**
     * Locates the start index of the first run of {@link ResponseWriterKt#DELIMITER_LEN} zero bytes, or
     * {@code -1} if no complete run is present. The JSON prelude never contains a raw NUL (the encoder
     * escapes U+0000), so the first such run unambiguously marks the metadata/body boundary.
     */
    private static int indexOfDelimiter(byte[] data) {
        int run = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                run++;
                if (run == ResponseWriterKt.DELIMITER_LEN) {
                    return i - ResponseWriterKt.DELIMITER_LEN + 1;
                }
            } else {
                run = 0;
            }
        }
        return -1;
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** The exact prelude + delimiter the handler commits for a 200 response of {@link #OK_SIZE}. */
    private static byte[] committedOkPrefix() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)
                .writeMetadata(
                        out,
                        new ResponseMetadata(
                                200,
                                Map.of("Content-Type", "application/octet-stream", "Content-Length", String.valueOf(OK_SIZE)),
                                null));
        return out.toByteArray();
    }

    /** Minimal {@code /{proxy+}} event carrying the requested file name in {@code pathParameters.proxy}. */
    private static byte[] proxyEvent(String fileName) {
        return ("{\"pathParameters\":{\"proxy\":\"" + fileName + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A sink that captures every byte handed to it and, when a non-negative {@code failAtByte} is set,
     * throws once that cumulative byte offset is reached. Bulk writes are performed one byte at a time
     * so a failure can strike partway through the prelude, the delimiter, or the body while the bytes
     * written before the failure remain captured for inspection.
     */
    private static final class CapturingFailingOutputStream extends OutputStream {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final int failAtByte; // -1 => never fail
        private int written = 0;

        CapturingFailingOutputStream(int failAtByte) {
            this.failAtByte = failAtByte;
        }

        @Override
        public void write(int b) throws IOException {
            if (failAtByte >= 0 && written >= failAtByte) {
                throw new IOException("simulated sink write failure at byte offset " + written);
            }
            captured.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        byte[] captured() {
            return captured.toByteArray();
        }
    }
}
