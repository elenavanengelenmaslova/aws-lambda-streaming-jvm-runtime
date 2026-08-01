package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

/**
 * Branch unit tests for {@link StreamHandler}'s existence-check mapping and status-committed-early
 * ordering, driven through the package-private constructor with a Mockito {@link S3Source} and the
 * real {@link RequestParser}, {@link FileNameValidator}, and library {@link ResponseWriter}.
 *
 * <p>Using the real parser/validator/writer means each test exercises the true wire format: a valid
 * {@code /{proxy+}} proxy event (built with Jackson so it is correctly escaped) naming an accepted
 * file flows through parse &rarr; validate &rarr; {@code head}, and only the S3 outcome is
 * substituted. The bytes written to a {@link ByteArrayOutputStream} are split on the
 * {@link ResponseWriterKt#DELIMITER_LEN 8-null-byte} delimiter and the metadata JSON prelude is
 * decoded with Jackson to assert the committed status and headers.
 *
 * <p>Covers the three {@code head} branches and the ordering guarantee:
 * <ul>
 *   <li>{@link HeadResult.NotFound} &rarr; HTTP 404, no body streamed (Req 4.2).</li>
 *   <li>{@link HeadResult.Failure} (generic failure or {@link ApiCallTimeoutException} timeout)
 *       &rarr; HTTP 502, no body streamed (Req 4.4).</li>
 *   <li>{@link HeadResult.Exists} &rarr; HTTP 200 with {@code Content-Type:
 *       application/octet-stream} and {@code Content-Length} equal to the reported size, written
 *       before any body byte (Req 4.3).</li>
 *   <li>head-before-metadata-before-body ordering: {@code source.head(...)} is invoked before
 *       {@code source.streamBody(...)} (Mockito {@link InOrder}), and the full metadata prelude +
 *       delimiter is written before {@code streamBody} emits any body byte (Req 4.1, 4.3).</li>
 * </ul>
 *
 * <p>Requirements: 4.2, 4.3, 4.4
 */
class StreamHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An accepted file name: allow-list characters only, no separators / parent-dir / prefix. */
    private static final String ACCEPTED_FILE = "report.bin";

    /** Distinctive, NUL-free body bytes so the only 8-zero run in the wire is the real delimiter. */
    private static final byte[] BODY = "STREAMED-BODY-BYTES".getBytes(StandardCharsets.UTF_8);

    private S3Source source;
    private StreamHandler handler;

    @BeforeEach
    void setUp() {
        // Mock only the S3 boundary; parser, validator, and the library writer are real so the
        // test drives the true parse/validate path and the true wire format.
        source = mock(S3Source.class);
        handler = new StreamHandler(
                new RequestParser(),
                new FileNameValidator(),
                source,
                new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN));
    }

    // ---- head branch mapping ----------------------------------------------------------------

    @Test
    @DisplayName("Given head returns NotFound When handled Then it commits 404 and streams no body")
    void headNotFoundMapsTo404() throws IOException {
        when(source.head(any())).thenReturn(new HeadResult.NotFound());

        byte[] wire = handle(proxyEvent(ACCEPTED_FILE));

        JsonNode prelude = decodePrelude(wire);
        assertEquals(404, prelude.get("statusCode").asInt(), "a missing object must commit HTTP 404");
        assertEquals("application/json", prelude.at("/headers/Content-Type").asText());
        assertEquals("The requested object was not found.", decodeBodyMessage(wire));
        // Pre-commit error: no file body is streamed (Req 4.2).
        verify(source, never()).streamBody(any(), any());
    }

    @Test
    @DisplayName("Given head returns a generic Failure When handled Then it commits 502 and streams no body")
    void headFailureMapsTo502() throws IOException {
        when(source.head(any())).thenReturn(new HeadResult.Failure(new RuntimeException("boom")));

        byte[] wire = handle(proxyEvent(ACCEPTED_FILE));

        JsonNode prelude = decodePrelude(wire);
        assertEquals(502, prelude.get("statusCode").asInt(), "a non-missing head failure must commit HTTP 502");
        assertEquals("application/json", prelude.at("/headers/Content-Type").asText());
        assertEquals("Object retrieval failed.", decodeBodyMessage(wire));
        verify(source, never()).streamBody(any(), any());
    }

    @Test
    @DisplayName("Given head fails with a timeout When handled Then it commits 502 and streams no body")
    void headTimeoutFailureMapsTo502() throws IOException {
        // The 10s existence-check timeout surfaces as a Failure carrying ApiCallTimeoutException;
        // the handler maps it to 502 exactly like any other head failure (Req 4.4).
        HeadResult.Failure timeout = new HeadResult.Failure(ApiCallTimeoutException.create(10_000L));
        when(source.head(any())).thenReturn(timeout);

        byte[] wire = handle(proxyEvent(ACCEPTED_FILE));

        JsonNode prelude = decodePrelude(wire);
        assertEquals(502, prelude.get("statusCode").asInt(), "a head timeout must commit HTTP 502");
        assertEquals("application/json", prelude.at("/headers/Content-Type").asText());
        verify(source, never()).streamBody(any(), any());
    }

    @Test
    @DisplayName("Given head returns Exists(size) When handled Then it commits 200 with Content-Length before the body")
    void existsWritesContentLengthAndOctetStreamBeforeBody() throws IOException {
        long size = 15L * 1024 * 1024; // ~15 MB Test_Object
        when(source.head(any())).thenReturn(new HeadResult.Exists(size));
        stubStreamBodyWriting(BODY);

        byte[] wire = handle(proxyEvent(ACCEPTED_FILE));

        // Metadata prelude: status 200 and the exact streaming headers, declared before the body.
        JsonNode prelude = decodePrelude(wire);
        assertEquals(200, prelude.get("statusCode").asInt(), "an existing object must commit HTTP 200");
        Map<String, String> headers =
                MAPPER.convertValue(prelude.get("headers"), new TypeReference<LinkedHashMap<String, String>>() {});
        assertEquals(
                Map.of("Content-Type", "application/octet-stream", "Content-Length", String.valueOf(size)),
                headers,
                "the prelude must declare octet-stream and Content-Length = Exists size before any body");

        // The body region (after the delimiter) is exactly what streamBody wrote — proving the
        // metadata prelude was committed first (Req 4.3).
        int delimiterStart = indexOfDelimiter(wire);
        byte[] body = Arrays.copyOfRange(wire, delimiterStart + ResponseWriterKt.DELIMITER_LEN, wire.length);
        assertArrayEquals(BODY, body, "the body must follow the metadata + delimiter");
    }

    // ---- ordering: head before metadata before body -----------------------------------------

    @Test
    @DisplayName("Given an existing object When handled Then head precedes metadata precedes streamBody body")
    void headIsInvokedBeforeMetadataAndStreamBody() throws IOException {
        long size = 1_048_576L;
        when(source.head(any())).thenReturn(new HeadResult.Exists(size));
        // Capture how many bytes are already in the sink at the instant streamBody starts writing:
        // that must equal the whole metadata prelude + delimiter, proving metadata precedes the body.
        long[] bytesBeforeBody = {-1L};
        doAnswer(invocation -> {
            OutputStream sink = invocation.getArgument(1);
            bytesBeforeBody[0] = ((ByteArrayOutputStream) sink).size();
            sink.write(BODY);
            return (long) BODY.length;
        }).when(source).streamBody(any(), any());

        byte[] wire = handle(proxyEvent(ACCEPTED_FILE));

        // head is invoked before streamBody, so the existence check precedes any body byte (Req 4.1).
        InOrder inOrder = inOrder(source);
        inOrder.verify(source).head(any());
        inOrder.verify(source).streamBody(any(), any());

        // The full metadata prelude + 8-null-byte delimiter was written before streamBody wrote
        // anything: the sink size at streamBody entry equals the metadata/body boundary (Req 4.3).
        int delimiterStart = indexOfDelimiter(wire);
        assertTrue(delimiterStart >= 0, "the 8-null-byte delimiter must be present");
        assertEquals(
                delimiterStart + ResponseWriterKt.DELIMITER_LEN,
                bytesBeforeBody[0],
                "streamBody must start writing only after the metadata prelude and delimiter are written");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Runs the handler against {@code eventJson} and returns the bytes written to the sink. */
    private byte[] handle(byte[] eventJson) throws IOException {
        InputStream input = new ByteArrayInputStream(eventJson);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handler.handleRequest(input, output, null);
        return output.toByteArray();
    }

    /** Stubs {@code streamBody} to write the given bytes to the sink and return the count. */
    private void stubStreamBodyWriting(byte[] body) throws IOException {
        doAnswer(invocation -> {
            OutputStream sink = invocation.getArgument(1);
            sink.write(body);
            return (long) body.length;
        }).when(source).streamBody(any(), any());
    }

    /** Builds a minimal, correctly escaped {@code /{proxy+}} proxy event carrying the file name. */
    private static byte[] proxyEvent(String fileName) throws IOException {
        ObjectNode event = MAPPER.createObjectNode();
        event.putObject("pathParameters").put("proxy", fileName);
        event.put("httpMethod", "GET");
        event.put("path", "/" + fileName);
        return MAPPER.writeValueAsBytes(event);
    }

    /** Decodes the metadata JSON prelude that precedes the 8-null-byte delimiter. */
    private static JsonNode decodePrelude(byte[] wire) throws IOException {
        int delimiterStart = indexOfDelimiter(wire);
        assertTrue(delimiterStart >= 0, "the 8-null-byte delimiter must be present");
        String preludeJson = new String(Arrays.copyOfRange(wire, 0, delimiterStart), StandardCharsets.UTF_8);
        return MAPPER.readTree(preludeJson);
    }

    /** Decodes the {@code {"message":...}} error body that follows the delimiter. */
    private static String decodeBodyMessage(byte[] wire) throws IOException {
        int delimiterStart = indexOfDelimiter(wire);
        byte[] body = Arrays.copyOfRange(wire, delimiterStart + ResponseWriterKt.DELIMITER_LEN, wire.length);
        return MAPPER.readTree(new String(body, StandardCharsets.UTF_8)).get("message").asText();
    }

    /**
     * Locates the start index of the first run of {@link ResponseWriterKt#DELIMITER_LEN} zero
     * bytes. The JSON prelude never contains a raw NUL (the encoder escapes U+0000) and the test
     * body bytes are NUL-free, so the first such run unambiguously marks the metadata/body boundary.
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
}
