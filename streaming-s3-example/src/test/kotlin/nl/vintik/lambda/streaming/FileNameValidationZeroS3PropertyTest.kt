package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.stream.Stream

/**
 * Property 4: File-name validation accepts exactly the safe set and never reaches S3 when rejecting.
 *
 * For all strings `s`, [FileNameValidator] accepts `s` **if and only if** every character is in
 * the allow-list (`A–Z`, `a–z`, `0–9`, `-`, `_`, `.`) and `s` has no path separator (`/` or `\`),
 * no parent-directory sequence (`..`), no absolute-path prefix (leading `/`, leading `\`, or a
 * drive-letter prefix like `C:`), is non-empty and not entirely whitespace, and has length `<= 1024`.
 * The biconditional is checked against an INDEPENDENT reference predicate ([isSafeReference]) that
 * reimplements the safe-set rule from scratch, so the test does not merely mirror the production code.
 *
 * Furthermore, for every string the validator rejects, driving [StreamHandler] with that name issues
 * ZERO S3 interactions (`coVerify { s3Source wasNot Called }` against a relaxed [S3Source] mock) and
 * the written response carries HTTP status 400.
 *
 * Validates: Requirements 1.2, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7
 */
@DisplayName(
    "Feature: s3-file-streaming-endpoint, Property 4: validator accepts iff safe and rejected names issue no S3 request",
)
class FileNameValidationZeroS3PropertyTest {

    private val validator = FileNameValidator()

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("candidates")
    fun `Given a candidate file name When validated Then accept iff safe and every rejection issues no S3 request and a 400`(
        @Suppress("UNUSED_PARAMETER") label: String,
        candidate: String,
    ) {
        val expectedSafe = isSafeReference(candidate)
        val actualAccepted = validator.validate(candidate) is ValidationResult.Valid

        // Biconditional: the validator accepts exactly the safe set (independent reference predicate).
        assertEquals(
            expectedSafe,
            actualAccepted,
            "validator accept/reject must match the independent safe-set reference for: <$candidate>",
        )

        if (!expectedSafe) {
            // Drive the full handler for every rejected name and prove zero S3 access + HTTP 400.
            val s3Source = mockk<StreamSource<FileRequest>>(relaxed = true)
            val handler = StreamHandler(
                requestResolver = { FileKeyResolver(validator = validator) },
                source = { s3Source },
                responseWriter = ::ResponseWriter,
            )
            val output = ByteArrayOutputStream()

            handler.handleRequest(
                ByteArrayInputStream(proxyEvent(candidate)),
                output,
                mockk<Context>(relaxed = true),
            )

            // Req 2.7 / 1.2 / 1.4: no S3 request is issued for a rejected name.
            coVerify { s3Source wasNot Called }
            // The committed status for a rejected name is 400.
            assertEquals(400, statusOf(output.toByteArray()), "rejected names must commit HTTP 400")
        }
    }

    companion object {
        private const val MAX_LENGTH = 1024

        /**
         * Independent reference predicate for the safe set (Req 2.1–2.6). Deliberately reimplemented
         * from the requirements rather than reusing [FileNameValidator], so the biconditional test is
         * a genuine cross-check.
         */
        @JvmStatic
        fun isSafeReference(s: String?): Boolean {
            if (s == null) return false
            if (s.isEmpty()) return false
            if (s.all { it.isWhitespace() }) return false
            if (s.length > MAX_LENGTH) return false
            // absolute-path prefixes: leading "/", leading "\", or drive-letter "X:"
            if (s.startsWith('/') || s.startsWith('\\')) return false
            if (s.length >= 2 && isAsciiLetter(s[0]) && s[1] == ':') return false
            // path separators
            if (s.any { it == '/' || it == '\\' }) return false
            // parent-directory sequence
            if (s.contains("..")) return false
            // allow-list: A-Z, a-z, 0-9, '-', '_', '.'
            return s.all { c ->
                isAsciiLetter(c) || c in '0'..'9' || c == '-' || c == '_' || c == '.'
            }
        }

        private fun isAsciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

        /** Builds an API Gateway `/{proxy+}` event whose `pathParameters.proxy` is [fileName]. */
        private fun proxyEvent(fileName: String): ByteArray =
            buildJsonObject {
                putJsonObject("pathParameters") {
                    put("proxy", fileName)
                }
            }.toString().toByteArray(Charsets.UTF_8)

        /**
         * Extracts the committed HTTP status from the streaming response bytes by decoding the
         * metadata JSON prefix that precedes the 8 null-byte delimiter (segment 1 of the protocol).
         */
        private fun statusOf(bytes: ByteArray): Int {
            val delimiterStart = indexOfDelimiter(bytes)
            require(delimiterStart >= 0) { "response is missing the 8 null-byte delimiter" }
            val metadataJson = bytes.copyOfRange(0, delimiterStart).decodeToString()
            return Json.decodeFromString<ResponseMetadata>(metadataJson).statusCode
        }

        /** Index of the first run of [DELIMITER_LEN] consecutive zero bytes, or -1 if absent. */
        private fun indexOfDelimiter(bytes: ByteArray): Int {
            var run = 0
            for (i in bytes.indices) {
                if (bytes[i].toInt() == 0) {
                    run++
                    if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
                } else {
                    run = 0
                }
            }
            return -1
        }

        @JvmStatic
        fun candidates(): Stream<Arguments> = Stream.of(
            // --- Acceptances (safe set, Req 2.1) ---
            Arguments.of("accept simple name", "report.pdf"),
            Arguments.of("accept hyphen-underscore-version", "my-file_v2.txt"),
            Arguments.of("accept single char", "a"),
            Arguments.of("accept multi-extension", "data.tar.gz"),
            Arguments.of("accept leading underscore", "_hidden"),
            Arguments.of("accept mixed allow-list", "A.B-C_D.9"),
            Arguments.of("accept 1024-char boundary", "a".repeat(1024)),

            // --- MISSING (Req 2.5, 1.2) ---
            Arguments.of("reject empty", ""),
            Arguments.of("reject all-whitespace", "   "),
            Arguments.of("reject tab-and-newline whitespace", "\t \n"),

            // --- TOO_LONG (Req 2.6, 1.4) ---
            Arguments.of("reject 1025-char over-length", "a".repeat(1025)),

            // --- ABSOLUTE_PATH (Req 2.4) ---
            Arguments.of("reject leading forward slash", "/etc/passwd"),
            Arguments.of("reject leading backslash", "\\windows\\system32"),
            Arguments.of("reject drive-letter prefix", "C:file.txt"),

            // --- PATH_SEPARATOR (Req 2.2) ---
            Arguments.of("reject embedded forward slash", "dir/file.txt"),
            Arguments.of("reject embedded backslash", "dir\\file.txt"),

            // --- PARENT_DIR (Req 2.3) ---
            Arguments.of("reject bare parent dir", ".."),
            Arguments.of("reject embedded double period", "file..txt"),

            // --- ILLEGAL_CHARACTER (Req 2.1) ---
            Arguments.of("reject embedded space", "file name.txt"),
            Arguments.of("reject unicode accent", "caf\u00e9.txt"),
            Arguments.of("reject percent sign", "100%"),
            Arguments.of("reject semicolon", "semi;colon"),
            Arguments.of("reject tab control char", "tab\there"),
            Arguments.of("reject non-drive colon", "ab:cd"),
        )
    }
}
