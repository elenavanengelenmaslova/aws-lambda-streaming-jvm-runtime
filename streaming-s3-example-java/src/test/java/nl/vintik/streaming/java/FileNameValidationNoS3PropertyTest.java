package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.stream.Stream;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 4: Validator accepts exactly the safe set and never reaches S3 when rejecting — from Java.
 *
 * <p>Two universal facts are checked together over one diverse set of valid and adversarial file
 * names:
 *
 * <ol>
 *   <li><b>Accept-iff-safe (biconditional).</b> For every candidate, {@link FileNameValidator}
 *       returns {@link ValidationResult.Valid} <em>if and only if</em> an <b>independent</b>
 *       reference predicate ({@link #isReferenceSafe(String)}) considers the name safe. The
 *       reference is a fresh reimplementation of the "safe" rule &mdash; non-blank, at most 1024
 *       characters, no {@code /} or {@code \} separator, no {@code ..} parent-directory sequence,
 *       no absolute or drive-letter prefix, and every character drawn from
 *       {@code A–Z a–z 0–9 - _ .} &mdash; and it never calls into {@link FileNameValidator}, so the
 *       comparison is a true cross-check rather than a tautology. Each case also pins the reference
 *       predicate to a hand-labelled expectation, so a mistake in the reference itself is caught.</li>
 *   <li><b>Zero S3 access on rejection.</b> Driving the real {@link StreamHandler} (real
 *       {@link RequestParser}, real {@link FileNameValidator}, real {@link ResponseWriter}) with a
 *       Mockito {@link S3Source}: every <em>rejected</em> name yields an HTTP 400 metadata prelude
 *       and {@link org.mockito.Mockito#verifyNoInteractions verifyNoInteractions} on the source
 *       &mdash; no {@code head} and no {@code getObject}/stream is ever issued for an unsafe name
 *       (Req 3.7). Every <em>accepted</em> name, by contrast, is allowed through to S3: the handler
 *       reaches {@code S3Source.head} (stubbed to return {@link HeadResult.NotFound} so nothing is
 *       streamed), proving validation does not block a safe name.</li>
 * </ol>
 *
 * <p><b>Building the input.</b> Each candidate is embedded as {@code pathParameters.proxy} in a
 * {@code /{proxy+}} API Gateway proxy event built with Jackson, so adversarial characters (quotes,
 * backslashes, control characters, non-ASCII) are JSON-escaped correctly and reach the parser
 * verbatim. A {@code null} candidate is embedded as a JSON null, which the parser lifts to the
 * empty string &mdash; still a rejected name, so the no-S3 guarantee is exercised identically.
 *
 * <p>The property identifier is carried in {@code @Tag} using the exact string from the design's
 * Correctness Properties, matching the convention used by {@link MetadataRoundTripPropertyTest} and
 * {@link StreamingByteIdenticalPropertyTest}.
 *
 * <p>Validates: Requirements 2.2, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7
 */
@Tag("Feature: java-s3-file-streaming-endpoint, Property 4: validator accepts iff safe and rejected names issue no S3 request")
class FileNameValidationNoS3PropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Longest character length the safe rule permits (Req 3.6). */
    private static final int MAX_LENGTH = 1024;

    private final FileNameValidator validator = new FileNameValidator();

    @ParameterizedTest(name = "[{index}] {0} (expected safe={2})")
    @MethodSource("cases")
    @DisplayName("Given a file name When validated and run through the handler Then accept iff safe and rejected names issue zero S3 requests")
    void validatorAcceptsIffSafeAndRejectedNamesReachNoS3(String label, String candidate, boolean expectedSafe)
            throws IOException {
        // --- Part 1: accept-iff-safe against the independent reference predicate ---
        boolean referenceSafe = isReferenceSafe(candidate);
        assertEquals(
                expectedSafe,
                referenceSafe,
                "reference predicate self-check failed for [" + label + "] — the hand-labelled "
                        + "expectation and the independent reference must agree");

        boolean accepted = validator.validate(candidate) instanceof ValidationResult.Valid;
        assertEquals(
                referenceSafe,
                accepted,
                "FileNameValidator must accept a name if and only if the independent reference "
                        + "predicate considers it safe, for [" + label + "]");

        // --- Part 2: drive the real handler with a Mockito S3Source ---
        S3Source source = mock(S3Source.class);
        if (accepted) {
            // A safe name must be allowed through to S3; stub head to NotFound so nothing streams.
            when(source.head(any(FileRequest.class))).thenReturn(new HeadResult.NotFound());
        }
        StreamHandler handler = new StreamHandler(new RequestParser(), validator, source, new ResponseWriter());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handler.handleRequest(proxyEvent(candidate), out, null);
        int status = decodeStatusCode(out.toByteArray());

        if (accepted) {
            // The handler reached S3 (head), then mapped NotFound to 404 without streaming a body.
            verify(source).head(any(FileRequest.class));
            verify(source, never()).streamBody(any(FileRequest.class), any(OutputStream.class));
            assertEquals(404, status, "an accepted name reaches head; stubbed NotFound maps to 404 for [" + label + "]");
        } else {
            // Rejected: 400 and not a single S3 call for the unsafe name (Req 2.2, 2.4, 3.7).
            assertEquals(400, status, "a rejected name must produce HTTP 400 for [" + label + "]");
            verifyNoInteractions(source);
        }
    }

    /**
     * Independent reimplementation of the "safe" rule, deliberately not delegating to
     * {@link FileNameValidator}. A name is safe when it is non-blank, at most {@link #MAX_LENGTH}
     * characters, free of path separators and {@code ..} sequences, carries no absolute or
     * drive-letter prefix, and consists only of allow-listed characters
     * ({@code A–Z a–z 0–9 - _ .}).
     */
    private static boolean isReferenceSafe(String name) {
        if (name == null || name.strip().isEmpty()) {
            return false; // non-blank (covers null, empty, and all-whitespace)
        }
        if (name.length() > MAX_LENGTH) {
            return false; // at most 1024 characters
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return false; // no path separator
        }
        if (name.contains("..")) {
            return false; // no parent-directory sequence
        }
        char first = name.charAt(0);
        boolean drivePrefix = name.length() >= 2 && isAsciiLetter(first) && name.charAt(1) == ':';
        if (first == '/' || first == '\\' || drivePrefix) {
            return false; // no absolute or drive-letter prefix
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return false; // allow-list only
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * Builds a minimal {@code /{proxy+}} API Gateway proxy event carrying {@code candidate} as the
     * {@code pathParameters.proxy} value, using Jackson so any adversarial characters are escaped
     * correctly. A {@code null} candidate is written as a JSON null (lifted to "" by the parser).
     */
    private static InputStream proxyEvent(String candidate) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("resource", "/{proxy+}");
        root.put("httpMethod", "GET");
        ObjectNode pathParameters = root.putObject("pathParameters");
        if (candidate == null) {
            pathParameters.putNull("proxy");
        } else {
            pathParameters.put("proxy", candidate);
        }
        return new ByteArrayInputStream(MAPPER.writeValueAsBytes(root));
    }

    /**
     * Reads the committed HTTP status code from a streaming response: the metadata JSON prelude is
     * everything before the first {@link ResponseWriterKt#DELIMITER_LEN}-byte run of zero bytes
     * (the encoder never emits a raw NUL inside the prelude).
     */
    private static int decodeStatusCode(byte[] wire) throws IOException {
        int delimiterStart = indexOfDelimiter(wire);
        assertTrue(delimiterStart >= 0, "the 8-null-byte delimiter must be present in the response");
        String preludeJson = new String(Arrays.copyOfRange(wire, 0, delimiterStart), StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(preludeJson);
        JsonNode statusCode = root.get("statusCode");
        assertTrue(statusCode != null, "the prelude must carry a statusCode field");
        return statusCode.asInt();
    }

    /** Locates the start index of the first run of {@link ResponseWriterKt#DELIMITER_LEN} zero bytes. */
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

    /**
     * 20 diverse cases in the 10&ndash;20 range: 7 safe names (including the 1024-char length
     * boundary) and 13 adversarial names covering every rejection family &mdash; missing/blank,
     * over-length, absolute and drive-letter prefixes, forward- and back-slash separators,
     * parent-directory sequences, and illegal characters (space, non-ASCII, and the JSON-significant
     * quote/backslash/control characters that stress correct event escaping).
     */
    static Stream<Arguments> cases() {
        return Stream.of(
                // --- safe: accepted by the validator, reachable at S3 ---
                Arguments.of("report.pdf", "report.pdf", true),
                Arguments.of("my-file_v2.txt", "my-file_v2.txt", true),
                Arguments.of("single-char 'a'", "a", true),
                Arguments.of("FILE123", "FILE123", true),
                Arguments.of("data.tar.gz", "data.tar.gz", true),
                Arguments.of("A.B-C_D.9 (all allow-list families)", "A.B-C_D.9", true),
                Arguments.of("1024-char boundary", "a".repeat(MAX_LENGTH), true),

                // --- adversarial: rejected, must issue zero S3 requests ---
                Arguments.of("null proxy value", null, false),
                Arguments.of("empty string", "", false),
                Arguments.of("all whitespace", "   ", false),
                Arguments.of("1025-char over-length", "a".repeat(MAX_LENGTH + 1), false),
                Arguments.of("leading-slash absolute path", "/etc/passwd", false),
                Arguments.of("leading-backslash absolute path", "\\windows", false),
                Arguments.of("drive-letter prefix", "C:file.txt", false),
                Arguments.of("embedded forward slash", "dir/file.txt", false),
                Arguments.of("embedded parent-dir sequence", "file..txt", false),
                Arguments.of("illegal space", "file name.txt", false),
                Arguments.of("non-ASCII character", "caf\u00e9.txt", false),
                Arguments.of("JSON-significant quote", "a\"b.txt", false),
                Arguments.of("control character (tab)", "tab\tafter", false));
    }
}
