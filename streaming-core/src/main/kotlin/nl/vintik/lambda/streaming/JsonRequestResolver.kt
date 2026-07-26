package nl.vintik.lambda.streaming

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Lenient [Json] instance for decoding Lambda event payloads.
 *
 * `ignoreUnknownKeys = true` lets callers read only the fields they care about while
 * tolerating the full API Gateway / EventBridge / custom-event shape.
 */
val LambdaJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Creates a [RequestResolver] that deserializes the Lambda `InputStream` as JSON into a
 * value of type [T] (using [serializer]`<T>()`), then delegates to [extract] to produce a
 * [RequestResult].
 *
 * This is the recommended overload for `@Serializable` data classes — the serializer is
 * derived automatically via reification:
 *
 * ```kotlin
 * @Serializable
 * data class GatewayEvent(val pathParameters: Map<String, String?>? = null)
 *
 * data class VideoRequest(val videoId: String, val quality: String)
 *
 * val resolver = jsonRequestResolver<GatewayEvent, VideoRequest> { event ->
 *     val id = event.pathParameters?.get("videoId")
 *         ?: return@jsonRequestResolver RequestResult.Error(400, "Missing video ID.")
 *     RequestResult.Resolved(VideoRequest(id, event.pathParameters["quality"] ?: "hd"))
 * }
 * ```
 *
 * Deserialization failures (empty stream, malformed JSON, missing required fields) are
 * caught and returned as [RequestResult.Error] 400 — no source request is issued.
 *
 * @param json      JSON instance to use; defaults to [LambdaJson] (`ignoreUnknownKeys = true`).
 * @param extract   Maps the decoded [T] to a [RequestResult].
 */
inline fun <reified T : Any, R : Any> jsonRequestResolver(
    json: Json = LambdaJson,
    noinline extract: (T) -> RequestResult<R>,
): RequestResolver<R> = jsonRequestResolver(serializer<T>(), json, extract)

/**
 * Creates a [RequestResolver] that deserializes the Lambda `InputStream` as JSON using an
 * explicit [deserializer], then delegates to [extract] to produce a [RequestResult].
 *
 * Use this overload when you need a custom [DeserializationStrategy] or when the reified
 * overload is not available (e.g. from Java).
 *
 * Deserialization failures are caught and returned as [RequestResult.Error] 400.
 */
fun <T : Any, R : Any> jsonRequestResolver(
    deserializer: DeserializationStrategy<T>,
    json: Json = LambdaJson,
    extract: (T) -> RequestResult<R>,
): RequestResolver<R> = RequestResolver { input ->
    runCatching {
        json.decodeFromString(deserializer, input.readBytes().decodeToString())
    }.fold(
        onSuccess = { extract(it) },
        onFailure = { RequestResult.Error(400, "The request could not be parsed.") },
    )
}
