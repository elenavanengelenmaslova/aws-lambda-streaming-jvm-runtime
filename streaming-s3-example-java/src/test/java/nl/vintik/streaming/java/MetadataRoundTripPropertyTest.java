package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * Property 1: Metadata round-trip preserves status and all headers — from Java.
 *
 * <p>For all {@link ResponseMetadata} values {@code m} built from Java — via the direct
 * constructor with {@code null} cookies, via the direct constructor with cookies present, and via
 * {@code ResponseMetadata.Companion.fromMultiValue(...)} — writing {@code m} through the library's
 * {@link ResponseWriter} and then decoding the metadata JSON reproduces the status code and every
 * header entry (with multi-value collapse and {@code Set-Cookie} routed to the cookies array).
 *
 * <p>This is the cross-language proof for Requirement 1: the same wire-format encoder the Kotlin
 * example uses is driven here purely from Java source, exercising the interop entry points the
 * design calls out — {@code new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)},
 * {@code new ResponseMetadata(int, Map, null)}, and {@code ResponseMetadata.Companion.fromMultiValue(...)}.
 *
 * <p>Decoding uses Jackson (already the module's Java JSON reader) rather than kotlinx — decoding
 * the prelude with an independent, idiomatic-Java reader is the stronger interop assertion: the
 * bytes the Kotlin library emits are consumed without relying on the Kotlin serializer.
 *
 * <p>The property identifier is carried in {@code @Tag} using the exact string from the design's
 * Correctness Properties, matching the {@code @Tag} convention the {@code :streaming-core} property
 * tests already use (Properties 2 and 3).
 *
 * <p>Validates: Requirements 1.2, 1.3, 5.1, 5.2
 */
@Tag("Feature: java-s3-file-streaming-endpoint, Property 1: metadata round-trip preserves statusCode and every header")
class MetadataRoundTripPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("metadataCases")
    @DisplayName("Given metadata built from Java When written by the library and decoded Then status and every header round-trip")
    void metadataWrittenByLibraryRoundTripsStatusAndHeaders(String label, ResponseMetadata metadata)
            throws IOException {
        // When — write through the library's ResponseWriter, constructed via the Java-visible
        // (Json, Integer) constructor: Java cannot use Kotlin default args, so Json.Default and the
        // top-level constant on the *Kt facade are passed explicitly (design interop table).
        ResponseWriter writer =
                new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeMetadata(out, metadata);
        byte[] wire = out.toByteArray();

        // Then — segments 1+2 only (metadata-only write): the JSON prelude, then exactly the
        // 8 null-byte delimiter, and nothing after it (Req 5.1 ordering).
        int delimiterStart = indexOfDelimiter(wire);
        assertTrue(delimiterStart >= 0, "the 8-null-byte delimiter must be present");
        assertEquals(
                delimiterStart + ResponseWriterKt.DELIMITER_LEN,
                wire.length,
                "writeMetadata must emit only the prelude followed by the delimiter (no body bytes)");

        String preludeJson =
                new String(Arrays.copyOfRange(wire, 0, delimiterStart), StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(preludeJson);

        // statusCode round-trips
        assertTrue(root.has("statusCode"), "the prelude must carry a statusCode field");
        assertEquals(metadata.getStatusCode(), root.get("statusCode").asInt(), "statusCode must round-trip");

        // every header name -> value round-trips, and Set-Cookie is never a header (Req 5.2)
        JsonNode headersNode = root.get("headers");
        assertNotNull(headersNode, "the prelude must carry a headers object");
        Map<String, String> decodedHeaders =
                MAPPER.convertValue(headersNode, new TypeReference<LinkedHashMap<String, String>>() {});
        for (String key : decodedHeaders.keySet()) {
            assertFalse(
                    key.equalsIgnoreCase(ResponseMetadata.SET_COOKIE),
                    "Set-Cookie must be routed to the cookies array, never emitted as a header: " + key);
        }
        assertEquals(metadata.getHeaders(), decodedHeaders, "every header name->value entry must round-trip");

        // cookies round-trip: present as an ordered JSON array, or omitted entirely when null
        List<String> expectedCookies = metadata.getCookies();
        JsonNode cookiesNode = root.get("cookies");
        if (expectedCookies == null) {
            assertNull(cookiesNode, "the cookies field must be omitted when the metadata carries no cookies");
        } else {
            assertNotNull(cookiesNode, "the cookies field must be present when the metadata carries cookies");
            assertTrue(cookiesNode.isArray(), "cookies must be encoded as a JSON array");
            List<String> decodedCookies =
                    MAPPER.convertValue(cookiesNode, new TypeReference<ArrayList<String>>() {});
            assertEquals(expectedCookies, decodedCookies, "every Set-Cookie value must round-trip in order");
        }
    }

    /**
     * Locates the start index of the first run of {@link ResponseWriterKt#DELIMITER_LEN} zero
     * bytes. The JSON prelude never contains a raw NUL (the encoder escapes U+0000), so the first
     * such run unambiguously marks the metadata/body boundary.
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

    private static Arguments direct(String label, int status, Map<String, String> headers, List<String> cookies) {
        // Exercises `new ResponseMetadata(int, Map, List)` — cookies is null for the no-cookies form.
        return Arguments.of(label, new ResponseMetadata(status, headers, cookies));
    }

    private static Arguments multiValue(String label, int status, Map<String, List<String>> headers) {
        // Exercises the companion accessor `ResponseMetadata.Companion.fromMultiValue(int, Map)`.
        return Arguments.of(label, ResponseMetadata.Companion.fromMultiValue(status, headers));
    }

    static Stream<Arguments> metadataCases() {
        return Stream.of(
                // --- Group A: direct ctor with null cookies -> new ResponseMetadata(int, Map, null) ---
                direct("direct ctor, empty headers, status 200", 200, Map.of(), null),
                direct(
                        "direct ctor, Content-Type only, status 200",
                        200,
                        Map.of("Content-Type", "application/octet-stream"),
                        null),
                direct("direct ctor, Content-Length only, status 200", 200, Map.of("Content-Length", "15728640"), null),
                direct(
                        "direct ctor, streaming headers type+length, status 200",
                        200,
                        Map.of("Content-Type", "application/octet-stream", "Content-Length", "1048576"),
                        null),
                direct("direct ctor, header with empty value, status 204", 204, Map.of("X-Empty", ""), null),
                direct(
                        "direct ctor, unicode header values, status 200",
                        200,
                        Map.of("X-Greeting", "h\u00e9llo, \u3053\u3093\u306b\u3061\u306f, \u041f\u0440\u0438\u0432\u0456\u0442, \ud83d\ude00\ud83d\ude80"),
                        null),
                direct(
                        "direct ctor, JSON-significant characters in value, status 200",
                        200,
                        Map.of("X-Raw", "a\"b\\c, {\"nested\":true}, line1\nline2, tab\there"),
                        null),
                direct("direct ctor, error content type, status 400", 400, Map.of("Content-Type", "application/json"), null),
                direct("direct ctor, error content type, status 404", 404, Map.of("Content-Type", "application/json"), null),
                direct("direct ctor, error content type, status 502", 502, Map.of("Content-Type", "application/json"), null),
                direct("direct ctor, edge status Integer.MAX_VALUE, empty headers", Integer.MAX_VALUE, Map.of(), null),

                // --- Group B: direct ctor with cookies present -> new ResponseMetadata(int, Map, List) ---
                direct(
                        "direct ctor, single cookie present, status 200",
                        200,
                        Map.of("Content-Type", "text/html"),
                        List.of("theme=dark; Path=/")),
                direct(
                        "direct ctor, multiple cookies incl comma-in-Expires plus header, status 200",
                        200,
                        Map.of("Content-Type", "text/html"),
                        List.of("sid=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure", "theme=dark; Path=/")),
                direct(
                        "direct ctor, cookies present, empty headers, status 200",
                        200,
                        Map.of(),
                        List.of("only=1")),

                // --- Group C: fromMultiValue -> ResponseMetadata.Companion.fromMultiValue(int, Map) ---
                multiValue(
                        "fromMultiValue, single-valued headers, status 200",
                        200,
                        Map.of(
                                "Content-Type", List.of("application/octet-stream"),
                                "Content-Length", List.of("1048576"))),
                multiValue(
                        "fromMultiValue, repeated header joined with comma-space, status 200",
                        200,
                        Map.of("Accept-Encoding", List.of("gzip", "deflate", "br"))),
                multiValue(
                        "fromMultiValue, Set-Cookie multi-value routed to cookies array, status 200",
                        200,
                        Map.of(
                                "Content-Type", List.of("text/html"),
                                "Set-Cookie",
                                        List.of("sid=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure", "theme=dark; Path=/"))),
                multiValue(
                        "fromMultiValue, mixed-case Set-Cookie spellings all collected, status 200",
                        200,
                        Map.of(
                                "set-cookie", List.of("a=1"),
                                "Set-Cookie", List.of("b=2"),
                                "SET-COOKIE", List.of("c=3"))),
                multiValue(
                        "fromMultiValue, empty value list maps to empty string, status 200",
                        200,
                        Map.of("X-Empty", List.of())));
    }
}
