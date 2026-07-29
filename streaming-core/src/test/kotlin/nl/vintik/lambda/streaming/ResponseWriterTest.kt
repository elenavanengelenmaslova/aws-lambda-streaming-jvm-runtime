package nl.vintik.lambda.streaming

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
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "15728640",
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
        val metadata = ResponseMetadata(200, mapOf("X-Empty" to ""))
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
        val metadata = ResponseMetadata(200, mapOf("Content-Length" to "0"))

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

    @Test
    fun `Given a body When writeResponse is called Then the body follows the delimiter byte for byte`() {
        // Given
        val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/plain"))
        val body = "hello streaming world".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        // When
        writer.writeResponse(out, metadata, body)

        // Then
        val bytes = out.toByteArray()
        val delimiterStart = indexOfDelimiter(bytes)
        assertEquals(
            metadata,
            json.decodeFromString<ResponseMetadata>(
                String(bytes.copyOfRange(0, delimiterStart), Charsets.UTF_8),
            ),
        )
        assertArrayEquals(body, bytes.copyOfRange(delimiterStart + DELIMITER_LEN, bytes.size))
    }

    @Test
    fun `Given no body When writeResponse is called Then nothing follows the delimiter`() {
        // Given
        val out = ByteArrayOutputStream()

        // When — a metadata-only response, e.g. 204.
        writer.writeResponse(out, ResponseMetadata(204, emptyMap()))

        // Then
        val bytes = out.toByteArray()
        assertEquals(indexOfDelimiter(bytes) + DELIMITER_LEN, bytes.size)
    }

    @Test
    fun `Given no configured limit When the prelude is large Then it is written without complaint`() {
        // Given — the default writer matches the AWS reference implementation, which validates
        // nothing. A prelude far past any commonly cited budget must still be written.
        val metadata = ResponseMetadata(200, mapOf("X-Big" to "v".repeat(OBSERVED_MAX_PRELUDE_LEN)))
        val out = ByteArrayOutputStream()

        // When
        writer.writeMetadata(out, metadata)

        // Then
        assertTrue(out.toByteArray().size > OBSERVED_MAX_PRELUDE_LEN)
    }

    @Test
    fun `Given a prelude exactly at the configured limit When writeMetadata is called Then it is written`() {
        // Given — a limit set to the exact encoded length: the boundary is inclusive.
        val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/plain"))
        val exactLen = json.encodeToString(metadata).toByteArray(Charsets.UTF_8).size
        val out = ByteArrayOutputStream()

        // When
        ResponseWriter(json, maxPreludeLen = exactLen).writeMetadata(out, metadata)

        // Then
        assertEquals(exactLen + DELIMITER_LEN, out.toByteArray().size)
    }

    @Test
    fun `Given a prelude one byte over the configured limit When writeMetadata is called Then it throws and writes nothing`() {
        // Given — one byte under the encoded length, so the prelude is oversized by exactly one.
        val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/plain"))
        val exactLen = json.encodeToString(metadata).toByteArray(Charsets.UTF_8).size
        val out = ByteArrayOutputStream()

        // When / Then
        assertThrows(MetadataTooLargeException::class.java) {
            ResponseWriter(json, maxPreludeLen = exactLen - 1).writeMetadata(out, metadata)
        }

        // The status is only committed once the delimiter is written, so an oversized prelude
        // must leave the stream completely untouched — the caller can still write a 502 instead.
        assertEquals(0, out.toByteArray().size, "no byte may be written when the prelude is rejected")
    }

    @Test
    fun `Given a configured limit When writeResponse is called with an oversized prelude Then no body bytes are written either`() {
        // Given
        val metadata = ResponseMetadata(200, mapOf("X-Big" to "v".repeat(512)))
        val out = ByteArrayOutputStream()

        // When / Then — the guard runs before the prelude, so the body never reaches the stream.
        assertThrows(MetadataTooLargeException::class.java) {
            ResponseWriter(json, maxPreludeLen = 64)
                .writeResponse(out, metadata, "body".toByteArray(Charsets.UTF_8))
        }
        assertEquals(0, out.toByteArray().size)
    }

    @Test
    fun `Given a header value containing a NUL character When writeMetadata is called Then the prelude carries no raw zero byte`() {
        // Given — AWS's reference implementation states a NUL byte is not allowed in the prelude,
        // since it would be indistinguishable from the delimiter. The JSON encoder escapes it.
        val nul = Char(0)
        val metadata = ResponseMetadata(200, mapOf("X-Nul" to "a${nul}b"))
        val out = ByteArrayOutputStream()

        // When
        writer.writeMetadata(out, metadata)

        // Then — the delimiter is the first zero byte, so the prelude before it has none.
        val bytes = out.toByteArray()
        val delimiterStart = indexOfDelimiter(bytes)
        val prelude = bytes.copyOfRange(0, delimiterStart)
        assertTrue(
            prelude.none { it == 0.toByte() },
            "the encoded prelude must not contain a raw NUL byte",
        )
        // ...and the value still round-trips intact.
        assertEquals(metadata, json.decodeFromString<ResponseMetadata>(String(prelude, Charsets.UTF_8)))
    }
}
