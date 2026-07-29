package nl.vintik.lambda.streaming

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ResponseMetadata.fromMultiValue] and the [ResponseMetadata.cookies] field.
 *
 * The prelude models headers as name -> single value, so multi-valued headers must be collapsed.
 * `Set-Cookie` cannot be collapsed by joining — a comma is legal inside a cookie `Expires` date,
 * so a joined value is ambiguous to clients — and is routed to the dedicated `cookies` array
 * instead. These tests pin both behaviours and the wire shape they produce.
 */
class ResponseMetadataFromMultiValueTest {

    private val json = Json

    @Test
    fun `Given single-valued headers When converted Then each value is carried through unchanged`() {
        // Given / When
        val metadata = ResponseMetadata.fromMultiValue(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to listOf("application/octet-stream"),
                "Content-Length" to listOf("1048576"),
            ),
        )

        // Then
        assertEquals(200, metadata.statusCode)
        assertEquals("application/octet-stream", metadata.headers["Content-Type"])
        assertEquals("1048576", metadata.headers["Content-Length"])
        assertNull(metadata.cookies)
    }

    @Test
    fun `Given a repeated header When converted Then its values are joined with a comma and space`() {
        // Given / When
        val metadata = ResponseMetadata.fromMultiValue(
            statusCode = 200,
            headers = mapOf("Accept-Encoding" to listOf("gzip", "deflate", "br")),
        )

        // Then
        assertEquals("gzip, deflate, br", metadata.headers["Accept-Encoding"])
    }

    @Test
    fun `Given an empty value list When converted Then the header maps to an empty string`() {
        // Given / When
        val metadata = ResponseMetadata.fromMultiValue(200, mapOf("X-Empty" to emptyList()))

        // Then
        assertEquals("", metadata.headers["X-Empty"])
    }

    @Test
    fun `Given Set-Cookie headers When converted Then they move to cookies and leave the header map`() {
        // Given — two cookies, one carrying a comma inside its Expires date. Joining these would
        // produce a value no client could split correctly.
        val expiring = "sid=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure"
        val plain = "theme=dark; Path=/"

        // When
        val metadata = ResponseMetadata.fromMultiValue(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to listOf("text/html"),
                "Set-Cookie" to listOf(expiring, plain),
            ),
        )

        // Then — cookies are kept discrete, and no Set-Cookie entry remains among the headers.
        assertEquals(listOf(expiring, plain), metadata.cookies)
        assertEquals(mapOf("Content-Type" to "text/html"), metadata.headers)
    }

    @Test
    fun `Given Set-Cookie spelled in mixed case across several keys When converted Then every value is collected`() {
        // Given — header maps are frequently case-insensitive at the source but not in Kotlin,
        // so the same header can arrive under more than one spelling.
        val metadata = ResponseMetadata.fromMultiValue(
            statusCode = 200,
            headers = mapOf(
                "set-cookie" to listOf("a=1"),
                "Set-Cookie" to listOf("b=2"),
                "SET-COOKIE" to listOf("c=3"),
            ),
        )

        // Then — all three are collected, and none is left behind as a joined header.
        assertEquals(listOf("a=1", "b=2", "c=3"), metadata.cookies)
        assertTrue(metadata.headers.isEmpty(), "no Set-Cookie spelling may survive in headers")
    }

    @Test
    fun `Given no cookies When encoded Then the cookies field is absent from the prelude`() {
        // Given — this is what keeps the wire format identical to a prelude that never had the
        // field, so adding cookies does not change the bytes for responses that do not use them.
        val metadata = ResponseMetadata.fromMultiValue(200, mapOf("Content-Type" to listOf("text/html")))

        // When
        val encoded = json.encodeToString(metadata)

        // Then
        assertFalse(encoded.contains("cookies"), "expected no cookies field in: $encoded")
        assertEquals("""{"statusCode":200,"headers":{"Content-Type":"text/html"}}""", encoded)
    }

    @Test
    fun `Given cookies When encoded Then they appear as a JSON array and round-trip`() {
        // Given
        val metadata = ResponseMetadata.fromMultiValue(
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("a=1", "b=2")),
        )

        // When
        val encoded = json.encodeToString(metadata)

        // Then
        assertEquals("""{"statusCode":200,"headers":{},"cookies":["a=1","b=2"]}""", encoded)
        assertEquals(metadata, json.decodeFromString<ResponseMetadata>(encoded))
    }

    @Test
    fun `Given an encoder configured to encode defaults When cookies are null Then the field is still omitted`() {
        // Given — a caller-supplied Json with encodeDefaults enabled. A null cookies field must
        // not leak into the prelude: AWS silently declines to apply metadata it considers
        // malformed, folding it into the response body instead.
        val encodeDefaults = Json { encodeDefaults = true }
        val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/html"))

        // When
        val encoded = encodeDefaults.encodeToString(metadata)

        // Then
        assertFalse(encoded.contains("cookies"), "expected no cookies field in: $encoded")
    }
}
