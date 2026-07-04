package nl.vintik.lambda.streaming

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [FileKeyResolver].
 *
 * Covers the three top-level resolution paths:
 *  - Malformed / empty input → [RequestResult.Error] 400 (parse failure).
 *  - Valid JSON with a rejected file name → [RequestResult.Error] 400 (validation failure).
 *  - Valid JSON with an accepted file name → [RequestResult.Resolved] carrying a [FileRequest].
 *
 * Detailed validation-rule coverage (all [ValidationResult.Reason] values, boundary lengths,
 * character-class combinations) is delegated to [FileNameValidatorTest] and
 * [FileNameValidationZeroS3PropertyTest], which exercise the rules exhaustively.
 */
class FileKeyResolverTest {

    private val resolver = FileKeyResolver()

    // --- Parse error → 400 ---

    @Test
    fun `Given empty input When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream(ByteArray(0)))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given malformed JSON When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream("not-json".toByteArray()))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    // --- Validation rejection → 400 ---

    @Test
    fun `Given missing file name When resolved Then returns Error 400`() {
        val input = proxyEvent("")
        val result = resolver.resolve(ByteArrayInputStream(input))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given file name with path separator When resolved Then returns Error 400`() {
        val input = proxyEvent("dir/file.txt")
        val result = resolver.resolve(ByteArrayInputStream(input))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given file name that is too long When resolved Then returns Error 400`() {
        val input = proxyEvent("a".repeat(1025))
        val result = resolver.resolve(ByteArrayInputStream(input))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    // --- Valid name → Resolved ---

    @Test
    fun `Given valid file name When resolved Then returns Resolved with FileRequest`() {
        val input = proxyEvent("report.bin")
        val result = resolver.resolve(ByteArrayInputStream(input))

        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(FileRequest("report.bin"), resolved.request)
    }

    private fun proxyEvent(fileName: String): ByteArray =
        buildJsonObject {
            putJsonObject("pathParameters") {
                put("proxy", fileName)
            }
        }.toString().toByteArray(Charsets.UTF_8)
}
