package nl.vintik.streaming.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Example-based unit tests for {@link RequestParser} (Req 2.1, 2.3).
 *
 * <p>Representative API Gateway {@code /{proxy+}} proxy events parse to a {@link StreamRequest}
 * carrying the file name lifted from {@code /pathParameters/proxy}; a well-formed event whose proxy
 * path parameter is absent (or null) still parses successfully to {@code Parsed(StreamRequest(""))}
 * — emptiness/length/character rules are the {@code FileNameValidator}'s job, not the parser's
 * (Req 2.1). Only input that cannot be decoded from the stream — malformed, empty, or truncated
 * JSON — yields {@link ParseResult.ParseError}, which the handler maps to HTTP 400 (Req 2.3).
 *
 * <p>Because the Java parser reads the event with Jackson, a bare-but-well-formed JSON value (an
 * array, number, or string) is a valid tree with no {@code /pathParameters/proxy} node, so it
 * parses to an empty file name rather than erroring — the parser errors only on genuinely
 * undecodable bytes.
 */
class RequestParserTest {

    private final RequestParser parser = new RequestParser();

    private ParseResult parse(String json) {
        return parser.parse(new ByteArrayInputStream(json.getBytes(UTF_8)));
    }

    /** Minimal {@code /{proxy+}} event carrying the requested file name in {@code pathParameters.proxy}. */
    private static String proxyEvent(String fileName) {
        return "{\"pathParameters\":{\"proxy\":\"" + fileName + "\"},\"httpMethod\":\"GET\",\"path\":\"/"
                + fileName + "\"}";
    }

    // --- Successful parse: file name lifted from /pathParameters/proxy (Req 2.1) ---

    @ParameterizedTest(name = "[{index}] proxy=\"{0}\"")
    @ValueSource(strings = {"report.pdf", "my-file_v2.txt", "data.tar.gz", "FILE123", "a"})
    @DisplayName("Given a proxy event with a proxy path parameter When parsed Then it yields the file name")
    void proxyEventYieldsFileName(String fileName) {
        ParseResult result = parse(proxyEvent(fileName));

        ParseResult.Parsed parsed = assertInstanceOf(ParseResult.Parsed.class, result);
        assertEquals(new StreamRequest(fileName), parsed.request());
    }

    @Test
    @DisplayName("Given a full API Gateway proxy event fixture When parsed Then the file name is still extracted")
    void fullProxyEventFixtureExtractsFileName() throws IOException {
        ParseResult result = parseResource("apigw-proxy-event.json");

        ParseResult.Parsed parsed = assertInstanceOf(ParseResult.Parsed.class, result);
        assertEquals(new StreamRequest("report.pdf"), parsed.request());
    }

    // --- Well-formed event, proxy path parameter absent/null -> Parsed(StreamRequest("")) (Req 2.1) ---

    @Test
    @DisplayName("Given a well-formed event without pathParameters When parsed Then it yields an empty file name")
    void eventWithoutPathParametersYieldsEmptyFileName() {
        // Parsing succeeds; emptiness is the validator's concern, not the parser's.
        ParseResult result = parse("{\"httpMethod\":\"GET\",\"path\":\"/\"}");

        ParseResult.Parsed parsed = assertInstanceOf(ParseResult.Parsed.class, result);
        assertEquals(new StreamRequest(""), parsed.request());
    }

    @Test
    @DisplayName("Given pathParameters present but no proxy key When parsed Then it yields an empty file name")
    void pathParametersWithoutProxyKeyYieldsEmptyFileName() {
        ParseResult result = parse("{\"pathParameters\":{\"other\":\"x\"},\"httpMethod\":\"GET\"}");

        ParseResult.Parsed parsed = assertInstanceOf(ParseResult.Parsed.class, result);
        assertEquals(new StreamRequest(""), parsed.request());
    }

    @Test
    @DisplayName("Given a proxy event with a null proxy value When parsed Then it yields an empty file name")
    void nullProxyValueYieldsEmptyFileName() {
        ParseResult result = parse("{\"pathParameters\":{\"proxy\":null}}");

        ParseResult.Parsed parsed = assertInstanceOf(ParseResult.Parsed.class, result);
        assertEquals(new StreamRequest(""), parsed.request());
    }

    // --- ParseError: input cannot be decoded from the stream (Req 2.3) ---

    @Test
    @DisplayName("Given empty input When parsed Then it returns ParseError")
    void emptyInputReturnsParseError() {
        ParseResult result = parser.parse(new ByteArrayInputStream(new byte[0]));

        assertInstanceOf(ParseResult.ParseError.class, result);
    }

    @ParameterizedTest(name = "[{index}] blank=\"{0}\"")
    @ValueSource(strings = {"   ", "\n", "\t \n"})
    @DisplayName("Given blank/whitespace-only input When parsed Then it returns ParseError")
    void blankInputReturnsParseError(String blank) {
        assertInstanceOf(ParseResult.ParseError.class, parse(blank));
    }

    @ParameterizedTest(name = "[{index}] json={0}")
    @ValueSource(strings = {"not json at all", "{ this is : broken }", "{\"pathParameters\": }", "}{"})
    @DisplayName("Given malformed input When parsed Then it returns ParseError")
    void malformedInputReturnsParseError(String json) {
        assertInstanceOf(ParseResult.ParseError.class, parse(json));
    }

    @ParameterizedTest(name = "[{index}] json={0}")
    @ValueSource(
            strings = {
                "{\"pathParameters\":{\"proxy\":\"report.pdf\"",
                "{\"pathParameters\":{",
                "{\"pathParameters\"",
                "{"
            })
    @DisplayName("Given truncated JSON When parsed Then it returns ParseError")
    void truncatedJsonReturnsParseError(String json) {
        assertInstanceOf(ParseResult.ParseError.class, parse(json));
    }

    private ParseResult parseResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/test-data/" + name)) {
            assertNotNull(in, "fixture must be present on the test classpath: /test-data/" + name);
            return parser.parse(in);
        }
    }
}
