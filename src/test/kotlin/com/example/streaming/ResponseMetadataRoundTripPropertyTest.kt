package com.example.streaming

import kotlinx.serialization.encodeToString
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
 * maps including multi-value, empty-value-list, and unicode-valued headers — decoding the JSON
 * produced by kotlinx-serialization reproduces an equal object: `decode(encode(m)) == m`,
 * preserving the statusCode and every header name -> value-list entry exactly.
 *
 * Validates: Requirements 4.2, 4.4
 */
@DisplayName(
    "Feature: s3-file-streaming-endpoint, Property 1: metadata round-trip preserves statusCode and every header name->value-list entry",
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
                ResponseMetadata(200, mapOf("Content-Type" to listOf("application/octet-stream"))),
            ),
            named(
                "single header with Content-Length, status 200",
                ResponseMetadata(200, mapOf("Content-Length" to listOf("15728640"))),
            ),
            named(
                "single header multi value (repeatable Set-Cookie), status 200",
                ResponseMetadata(
                    200,
                    mapOf("Set-Cookie" to listOf("a=1; Path=/", "b=2; Secure", "c=3; HttpOnly")),
                ),
            ),
            named(
                "multiple headers mixed cardinality, status 200",
                ResponseMetadata(
                    200,
                    mapOf(
                        "Content-Type" to listOf("application/octet-stream"),
                        "Content-Length" to listOf("1048576"),
                        "Set-Cookie" to listOf("x=1", "y=2"),
                    ),
                ),
            ),
            named(
                "header with empty value list, status 204",
                ResponseMetadata(204, mapOf("X-Empty" to emptyList())),
            ),
            named(
                "mix of empty and non-empty value lists, status 200",
                ResponseMetadata(
                    200,
                    mapOf(
                        "X-Empty" to emptyList(),
                        "X-Present" to listOf("value"),
                    ),
                ),
            ),
            named(
                "unicode header values, status 200",
                ResponseMetadata(
                    200,
                    mapOf("X-Greeting" to listOf("héllo", "こんにちは", "Привіт", "😀🚀")),
                ),
            ),
            named(
                "value containing JSON-significant characters, status 200",
                ResponseMetadata(
                    200,
                    mapOf("X-Raw" to listOf("a\"b\\c", "{\"nested\":true}", "line1\nline2", "tab\there")),
                ),
            ),
            named(
                "empty-string value and empty-string name, status 200",
                ResponseMetadata(200, mapOf("" to listOf(""), "X-Blank" to listOf(""))),
            ),
            named("status 400 with error content type", ResponseMetadata(400, mapOf("Content-Type" to listOf("application/json")))),
            named("status 404 with error content type", ResponseMetadata(404, mapOf("Content-Type" to listOf("application/json")))),
            named("status 502 with error content type", ResponseMetadata(502, mapOf("Content-Type" to listOf("application/json")))),
            named("status 0 edge value, empty headers", ResponseMetadata(0, emptyMap())),
            named("negative status code, empty headers", ResponseMetadata(-1, emptyMap())),
            named("large status code, empty headers", ResponseMetadata(Int.MAX_VALUE, emptyMap())),
            named(
                "many headers each many values, status 200",
                ResponseMetadata(
                    200,
                    (1..6).associate { i -> "X-Header-$i" to (1..i).map { "v$it" } },
                ),
            ),
            named(
                "duplicate-looking value entries preserved with multiplicity, status 200",
                ResponseMetadata(200, mapOf("X-Dup" to listOf("same", "same", "same"))),
            ),
            named(
                "whitespace-only values, status 200",
                ResponseMetadata(200, mapOf("X-Space" to listOf(" ", "  ", "\t"))),
            ),
            named(
                "realistic 200 streaming headers",
                ResponseMetadata(
                    200,
                    mapOf(
                        "Content-Type" to listOf("application/octet-stream"),
                        "Content-Length" to listOf("15728640"),
                    ),
                ),
            ),
        )

        private fun named(label: String, metadata: ResponseMetadata): Arguments =
            Arguments.of(label, metadata)
    }
}
