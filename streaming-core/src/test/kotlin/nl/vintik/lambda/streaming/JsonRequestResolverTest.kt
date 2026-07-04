package nl.vintik.lambda.streaming

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [jsonRequestResolver] — both the reified and explicit-deserializer overloads.
 *
 * Covers:
 *  - Successful deserialization → [RequestResult.Resolved] from [extract].
 *  - [extract] returning [RequestResult.Error] — field-level validation failure.
 *  - Empty input → [RequestResult.Error] 400 (JSON parse failure).
 *  - Malformed JSON → [RequestResult.Error] 400.
 *  - Custom [Json] instance is respected.
 *  - Explicit [kotlinx.serialization.DeserializationStrategy] overload.
 */
class JsonRequestResolverTest {

    @Serializable
    data class TestEvent(val id: String? = null, val name: String? = null)

    data class TestRequest(val id: String, val name: String)

    // --- Reified overload ---

    @Test
    fun `Given valid JSON When resolved Then extract receives decoded object and returns Resolved`() {
        val resolver = jsonRequestResolver<TestEvent, TestRequest> { event ->
            RequestResult.Resolved(TestRequest(event.id!!, event.name!!))
        }

        val result = resolver.resolve("""{"id":"42","name":"test"}""".stream())

        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(TestRequest("42", "test"), resolved.request)
    }

    @Test
    fun `Given valid JSON but extract returns Error When resolved Then Error is returned`() {
        val resolver = jsonRequestResolver<TestEvent, TestRequest> { _ ->
            RequestResult.Error(422, "Validation failed.")
        }

        val result = resolver.resolve("""{"id":"42"}""".stream())

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(422, error.statusCode)
    }

    @Test
    fun `Given missing required field in extract When resolved Then Error from extract is returned`() {
        val resolver = jsonRequestResolver<TestEvent, TestRequest> { event ->
            val id = event.id ?: return@jsonRequestResolver RequestResult.Error(400, "Missing id.")
            RequestResult.Resolved(TestRequest(id, event.name ?: "default"))
        }

        val result = resolver.resolve("""{"name":"test"}""".stream())

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given empty input When resolved Then returns Error 400`() {
        val resolver = jsonRequestResolver<TestEvent, TestRequest> { event ->
            RequestResult.Resolved(TestRequest(event.id!!, event.name!!))
        }

        val result = resolver.resolve("".stream())

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given malformed JSON When resolved Then returns Error 400`() {
        val resolver = jsonRequestResolver<TestEvent, TestRequest> { event ->
            RequestResult.Resolved(TestRequest(event.id!!, event.name!!))
        }

        val result = resolver.resolve("not-json".stream())

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given custom Json instance When resolved Then it is used for decoding`() {
        val strictJson = Json { ignoreUnknownKeys = false }
        val resolver = jsonRequestResolver<TestEvent, TestRequest>(json = strictJson) { event ->
            RequestResult.Resolved(TestRequest(event.id!!, event.name ?: ""))
        }

        // Strict JSON rejects unknown keys
        val result = resolver.resolve("""{"id":"1","unknown":"value"}""".stream())

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    // --- Explicit deserializer overload ---

    @Test
    fun `Given explicit deserializer When resolved Then behaves identically to reified overload`() {
        val resolver = jsonRequestResolver(
            deserializer = TestEvent.serializer(),
            extract = { event: TestEvent ->
                val id = event.id ?: return@jsonRequestResolver RequestResult.Error(400, "Missing id.")
                RequestResult.Resolved(TestRequest(id, event.name ?: "default"))
            },
        )

        val result = resolver.resolve("""{"id":"99","name":"explicit"}""".stream())

        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(TestRequest("99", "explicit"), resolved.request)
    }

    private fun String.stream() = ByteArrayInputStream(toByteArray(Charsets.UTF_8))
}
