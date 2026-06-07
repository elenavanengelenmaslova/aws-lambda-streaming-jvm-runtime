package com.example.streaming

import com.example.streaming.ParseResult.Parsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream

/**
 * Example-based unit tests for [RequestParser] (Req 1.1, 1.3).
 *
 * Representative API Gateway `/{proxy+}` proxy events parse to a [StreamRequest]
 * carrying the file name lifted from `pathParameters["proxy"]`; malformed, empty,
 * and truncated input decode to [ParseResult.ParseError] (mapped to HTTP 400 by
 * the Stream_Handler). The parser does not enforce emptiness/length/character
 * rules — that is the File_Name_Validator's job — so a present-but-empty proxy
 * value still parses successfully.
 */
class RequestParserTest {

    private val parser = RequestParser()

    private fun parse(json: String): ParseResult =
        parser.parse(ByteArrayInputStream(json.toByteArray()))

    private fun proxyEvent(fileName: String): String =
        """{"pathParameters":{"proxy":"$fileName"},"httpMethod":"GET","path":"/$fileName"}"""

    // --- Successful parse (Req 1.1) ---

    @ParameterizedTest
    @ValueSource(
        strings = [
            "report.pdf",
            "my-file_v2.txt",
            "data.tar.gz",
            "FILE123",
            "a",
        ],
    )
    fun `Given a proxy event with a proxy path parameter When parsed Then it yields the file name`(fileName: String) {
        val result = parse(proxyEvent(fileName))

        val parsed = assertInstanceOf(Parsed::class.java, result)
        assertEquals(StreamRequest(fileName), parsed.request)
    }

    @Test
    fun `Given a full proxy event with extra unknown fields When parsed Then the file name is still extracted`() {
        val json = """
            {
              "resource": "/{proxy+}",
              "path": "/report.pdf",
              "httpMethod": "GET",
              "headers": { "Accept": "*/*" },
              "pathParameters": { "proxy": "report.pdf" },
              "requestContext": { "stage": "prod" },
              "body": null,
              "isBase64Encoded": false
            }
        """.trimIndent()

        val result = parse(json)

        val parsed = assertInstanceOf(Parsed::class.java, result)
        assertEquals(StreamRequest("report.pdf"), parsed.request)
    }

    @Test
    fun `Given a proxy event without path parameters When parsed Then it yields an empty file name for the validator to reject`() {
        // Parsing succeeds; emptiness is the validator's concern (Req 1.2), not the parser's.
        val result = parse("""{"httpMethod":"GET","path":"/"}""")

        val parsed = assertInstanceOf(Parsed::class.java, result)
        assertEquals(StreamRequest(""), parsed.request)
    }

    @Test
    fun `Given a proxy event with a null proxy value When parsed Then it yields an empty file name`() {
        val result = parse("""{"pathParameters":{"proxy":null}}""")

        val parsed = assertInstanceOf(Parsed::class.java, result)
        assertEquals(StreamRequest(""), parsed.request)
    }

    // --- ParseError (Req 1.3) ---

    @Test
    fun `Given empty input When parsed Then it returns ParseError`() {
        val result = parser.parse(ByteArrayInputStream(ByteArray(0)))

        assertEquals(ParseResult.ParseError, result)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not json at all",
            "{ this is : broken }",
            "[1, 2, 3]",
            "12345",
            "\"a bare string\"",
        ],
    )
    fun `Given malformed input When parsed Then it returns ParseError`(json: String) {
        assertEquals(ParseResult.ParseError, parse(json))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"pathParameters\":{\"proxy\":\"report.pdf\"",
            "{\"pathParameters\":{",
            "{\"pathParameters\"",
            "{",
        ],
    )
    fun `Given truncated JSON When parsed Then it returns ParseError`(json: String) {
        assertEquals(ParseResult.ParseError, parse(json))
    }
}
