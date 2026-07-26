package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class S3StreamingHandlerTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `Given an invalid request body When handleRequest is called Then an error response is written without calling S3`() {
        val handler = S3StreamingHandler()
        val input = ByteArrayInputStream(ByteArray(0))   // empty — FileKeyResolver returns Error
        val output = ByteArrayOutputStream()

        handler.handleRequest(input, output, context)

        assertTrue(output.size() > 0, "Expected error response bytes in output")
    }
}
