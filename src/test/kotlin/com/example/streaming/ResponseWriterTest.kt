package com.example.streaming

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Example-based unit tests for [ResponseWriter] (Req 4.1).
 *
 * These pin the wire format of the API Gateway streaming response protocol:
 * metadata JSON (UTF-8) -> exactly [DELIMITER_LEN] zero bytes -> body bytes. The
 * metadata JSON never contains a NUL byte (the round-trip property, task 3.2,
 * guards this), so the first run of 8 consecutive zero bytes in the output marks
 * the metadata/body boundary and is used here to split the segments.
 */
class ResponseWriterTest {

    private val json = Json
    private val writer = ResponseWriter(json)

    /**
     * Index of the first run of exactly [DELIMITER_LEN] consecutive zero bytes, or -1 if
     * no such run exists. The metadata JSON carries no NUL byte, so this is the delimiter.
     */
    private fun indexOfDelimiter(bytes: ByteArray): Int {
        var i = 0
        while (i + DELIMITER_LEN <= bytes.size) {
            if ((0 until DELIMITER_LEN).all { bytes[i + it] == 0.toByte() }) return i
            i++
        }
        return -1
    }

    @Test
    fun `Given response metadata When writeMetadata is called Then a metadata JSON prelude is followed by exactly 8 zero bytes and no body`() {
        // Given
        val metadata = ResponseMetadata(
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to listOf("application/octet-stream"),
                "Content-Length" to listOf("15728640"),
            ),
        )
        val out = ByteArrayOutputStream()

        // When
        writer.writeMetadata(out, metadata)

        // Then
        val bytes = out.toByteArray()
        val delimiterStart = indexOfDelimiter(bytes)
        assertTrue(delimiterStart >= 0, "expected an 8 zero-byte delimiter in the output")

        // The prelude decodes back to the original metadata (Req 4.1).
        val preludeJson = String(bytes.copyOfRange(0, delimiterStart), Charsets.UTF_8)
        assertEquals(metadata, json.decodeFromString<ResponseMetadata>(preludeJson))

        // Exactly 8 zero bytes form the delimiter at the metadata/body boundary.
        val delimiter = bytes.copyOfRange(delimiterStart, delimiterStart + DELIMITER_LEN)
        assertArrayEquals(ByteArray(DELIMITER_LEN), delimiter)

        // writeMetadata writes segments 1 + 2 only — no body bytes follow the delimiter.
        assertEquals(
            delimiterStart + DELIMITER_LEN,
            bytes.size,
            "writeMetadata must not write any bytes after the delimiter",
        )
    }

    @Test
    fun `Given the prelude has no NUL byte When writeMetadata is called Then the first zero byte is the start of the 8-byte delimiter`() {
        // Given
        val metadata = ResponseMetadata(200, mapOf("X-Empty" to emptyList()))
        val out = ByteArrayOutputStream()

        // When
        writer.writeMetadata(out, metadata)

        // Then — the first zero byte in the output is the delimiter start, proving the
        // metadata JSON itself carries no NUL byte that could be mistaken for the boundary.
        val bytes = out.toByteArray()
        val firstZero = bytes.indexOfFirst { it == 0.toByte() }
        assertEquals(indexOfDelimiter(bytes), firstZero)
    }

    @Test
    fun `Given an error status and message When writeError is called Then an ErrorBody JSON follows the delimiter and carries the message`() {
        // Given
        val status = 404
        val message = "object not found"
        val out = ByteArrayOutputStream()

        // When
        writer.writeError(out, status, message)

        // Then
        val bytes = out.toByteArray()
        val delimiterStart = indexOfDelimiter(bytes)
        assertTrue(delimiterStart >= 0, "expected an 8 zero-byte delimiter in the error output")

        // Segment 1: metadata carries the error status.
        val preludeJson = String(bytes.copyOfRange(0, delimiterStart), Charsets.UTF_8)
        val metadata = json.decodeFromString<ResponseMetadata>(preludeJson)
        assertEquals(status, metadata.statusCode)

        // Segment 2: exactly 8 zero bytes.
        assertArrayEquals(
            ByteArray(DELIMITER_LEN),
            bytes.copyOfRange(delimiterStart, delimiterStart + DELIMITER_LEN),
        )

        // Segment 3: an ErrorBody JSON (not file body bytes) carrying the message.
        val bodyJson = String(
            bytes.copyOfRange(delimiterStart + DELIMITER_LEN, bytes.size),
            Charsets.UTF_8,
        )
        assertEquals(ErrorBody(message), json.decodeFromString<ErrorBody>(bodyJson))
    }

    @Test
    fun `Given an error response When writeError is called Then the body after the delimiter is exactly the ErrorBody and contains no file body bytes`() {
        // Given
        val status = 502
        val message = "retrieval failed"
        val out = ByteArrayOutputStream()

        // When
        writer.writeError(out, status, message)

        // Then — the bytes after the delimiter equal the serialized ErrorBody exactly,
        // proving no extra file body bytes are appended to an error response.
        val bytes = out.toByteArray()
        val delimiterStart = indexOfDelimiter(bytes)
        val body = bytes.copyOfRange(delimiterStart + DELIMITER_LEN, bytes.size)
        val expectedBody = json.encodeToString(ErrorBody(message)).toByteArray(Charsets.UTF_8)
        assertArrayEquals(expectedBody, body)
    }

    /** An [OutputStream] that fails on every write, used to exercise the write-failure path. */
    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("write failed")
        override fun write(b: ByteArray): Unit = throw IOException("write failed")
        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("write failed")
    }

    @Test
    fun `Given the output fails on write When writeMetadata is called Then the failure propagates and the status is never silently committed`() {
        // Given an output that throws on the very first write (Req 4.6).
        val metadata = ResponseMetadata(200, mapOf("Content-Length" to listOf("0")))

        // When / Then — the write failure propagates rather than being swallowed.
        assertThrows(IOException::class.java) {
            writer.writeMetadata(FailingOutputStream(), metadata)
        }
    }

    @Test
    fun `Given the output fails on write When writeError is called Then the failure propagates`() {
        // When / Then — a failure while writing an error response propagates (Req 4.6).
        assertThrows(IOException::class.java) {
            writer.writeError(FailingOutputStream(), 502, "retrieval failed")
        }
    }
}
