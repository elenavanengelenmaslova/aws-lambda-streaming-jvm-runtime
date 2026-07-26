package nl.vintik.lambda.streaming

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Property 1: Metadata round-trip preserves status and all headers.
 *
 * For all [ResponseMetadata] values `m` — over arbitrary HTTP status codes and arbitrary header
 * maps including varied string values, empty strings, and unicode-valued headers — decoding the
 * JSON produced by kotlinx-serialization reproduces an equal object: `decode(encode(m)) == m`,
 * preserving the statusCode and every header name -> value entry exactly.
 *
 * Validates: Requirements 4.2, 4.4
 */
@DisplayName(
    "Feature: s3-file-streaming-endpoint, Property 1: metadata round-trip preserves statusCode and every header name->value entry",
)
class ResponseMetadataRoundTripPropertyTest {

    private val json = Json

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("metadataCases")
    fun `Given a ResponseMetadata When encoded to JSON then decoded back Then the result equals the original preserving statusCode and every header entry`(
        label: String,
        metadata: ResponseMetadata,
    ) {
        // When
        val encoded = json.encodeToString(metadata)
        val decoded = json.decodeFromString<ResponseMetadata>(encoded)

        // Then
        assertEquals(metadata, decoded)
        assertEquals(metadata.statusCode, decoded.statusCode)
        assertEquals(metadata.headers, decoded.headers)
    }

    companion object {
        @JvmStatic
        fun metadataCases(): Stream<Arguments> = Stream.of(
            named("empty headers, status 200", ResponseMetadata(200, emptyMap())),
            named(
                "single header single value, status 200",
                ResponseMetadata(200, mapOf("Content-Type" to "application/octet-stream")),
            ),
            named(
                "single header with Content-Length, status 200",
                ResponseMetadata(200, mapOf("Content-Length" to "15728640")),
            ),
            named(
                "single header multi value joined (repeatable Set-Cookie), status 200",
                ResponseMetadata(
                    200,
                    mapOf("Set-Cookie" to "a=1; Path=/, b=2; Secure, c=3; HttpOnly"),
                ),
            ),
            named(
                "multiple headers mixed, status 200",
                ResponseMetadata(
                    200,
                    mapOf(
                        "Content-Type" to "application/octet-stream",
                        "Content-Length" to "1048576",
                        "Set-Cookie" to "x=1, y=2",
                    ),
                ),
            ),
            named(
                "header with empty value, status 204",
                ResponseMetadata(204, mapOf("X-Empty" to "")),
            ),
            named(
                "mix of empty and non-empty values, status 200",
                ResponseMetadata(
                    200,
                    mapOf(
                        "X-Empty" to "",
                        "X-Present" to "value",
                    ),
                ),
            ),
            named(
                "unicode header values, status 200",
                ResponseMetadata(
                    200,
                    mapOf("X-Greeting" to "héllo, こんにちは, Привіт, 😀🚀"),
                ),
            ),
            named(
                "value containing JSON-significant characters, status 200",
                ResponseMetadata(
                    200,
                    mapOf("X-Raw" to "a\"b\\c, {\"nested\":true}, line1\nline2, tab\there"),
                ),
            ),
            named(
                "empty-string value and empty-string name, status 200",
                ResponseMetadata(200, mapOf("" to "", "X-Blank" to "")),
            ),
            named("status 400 with error content type", ResponseMetadata(400, mapOf("Content-Type" to "application/json"))),
            named("status 404 with error content type", ResponseMetadata(404, mapOf("Content-Type" to "application/json"))),
            named("status 502 with error content type", ResponseMetadata(502, mapOf("Content-Type" to "application/json"))),
            named("status 0 edge value, empty headers", ResponseMetadata(0, emptyMap())),
            named("negative status code, empty headers", ResponseMetadata(-1, emptyMap())),
            named("large status code, empty headers", ResponseMetadata(Int.MAX_VALUE, emptyMap())),
            named(
                "many headers each with incrementing values, status 200",
                ResponseMetadata(
                    200,
                    (1..6).associate { i -> "X-Header-$i" to (1..i).joinToString(", ") { "v$it" } },
                ),
            ),
            named(
                "duplicate-looking repeated value preserved, status 200",
                ResponseMetadata(200, mapOf("X-Dup" to "same, same, same")),
            ),
            named(
                "whitespace-only values, status 200",
                ResponseMetadata(200, mapOf("X-Space" to " ,   , \t")),
            ),
            named(
                "realistic 200 streaming headers",
                ResponseMetadata(
                    200,
                    mapOf(
                        "Content-Type" to "application/octet-stream",
                        "Content-Length" to "15728640",
                    ),
                ),
            ),
        )

        private fun named(label: String, metadata: ResponseMetadata): Arguments =
            Arguments.of(label, metadata)
    }
}
