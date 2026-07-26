package nl.vintik.lambda.streaming

import nl.vintik.lambda.streaming.ParseResult.Parsed
import nl.vintik.lambda.streaming.ParseResult.ParseError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/**
 * Path-parameter key produced by the API Gateway `/{proxy+}` greedy proxy
 * integration. The requested file name arrives as `pathParameters["proxy"]`.
 */
private const val PROXY_PATH_PARAMETER = "proxy"

/**
 * Shared lenient [Json] used to decode the API Gateway proxy event. `ignoreUnknownKeys`
 * lets us read only the fields we care about (the proxy path parameter) while tolerating
 * the full `aws-lambda-java-events` proxy-event shape.
 */
val LenientJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Parses the API Gateway proxy event off the Lambda `InputStream` into a [StreamRequest].
 *
 * Only the requested file name is extracted (from the `/{proxy+}` path parameter); the
 * rest of the event shape is ignored via the lenient [Json]. Emptiness, whitespace, length,
 * and character rules are NOT enforced here — that is the File_Name_Validator's job, and the
 * Stream_Handler maps a missing/whitespace/over-length name to HTTP 400 (Req 1.2, 1.4).
 *
 * Returns [ParseError] only when the event itself cannot be decoded from the stream
 * (malformed, empty, or truncated input), which the handler maps to HTTP 400 (Req 1.3).
 */
class RequestParser(private val json: Json = LenientJson) {

    fun parse(input: InputStream): ParseResult =
        runCatching {
            val raw = input.readBytes().decodeToString()
            val event = json.decodeFromString<ProxyEvent>(raw)
            StreamRequest(event.fileName())
        }.fold(
            onSuccess = { Parsed(it) },
            onFailure = {
                logger.warn(it) { "Could not parse API Gateway proxy event from input stream" }
                ParseError
            },
        )
}

/**
 * Minimal lenient projection of the API Gateway proxy event: only the path parameters are
 * read so the requested file name can be lifted out. All other event fields are ignored by
 * the lenient [Json] (`ignoreUnknownKeys = true`).
 */
@Serializable
private data class ProxyEvent(
    val pathParameters: Map<String, String?>? = null,
) {
    /**
     * The requested file name from the `/{proxy+}` path parameter, or an empty string when
     * absent/null — left for the validator to reject (Req 1.2).
     */
    fun fileName(): String = pathParameters?.get(PROXY_PATH_PARAMETER).orEmpty()
}
