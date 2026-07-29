@file:OptIn(ExperimentalSerializationApi::class)

package nl.vintik.lambda.streaming

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Separator used when collapsing repeated header values into a single field value. */
private const val HEADER_VALUE_SEPARATOR = ", "

/**
 * Metadata document serialized as segment 1 of the API Gateway streaming response
 * protocol (metadata JSON → 8 null bytes → body).
 *
 * Headers are modeled as name → single string value, matching the format API Gateway expects for
 * the streaming protocol metadata prelude. Repeated headers must therefore be collapsed into one
 * value — see [fromMultiValue], which joins them with `", "`.
 *
 * `Set-Cookie` is the exception: comma-joining cookies corrupts them, because a comma is legal
 * inside a cookie's `Expires` date and clients would mis-split the result. The prelude carries a
 * dedicated [cookies] array for that reason, and [fromMultiValue] routes `Set-Cookie` into it.
 *
 * @param cookies repeatable `Set-Cookie` values. Omitted from the JSON entirely when null, which
 *   keeps the wire format identical to a prelude that never had the field.
 */
@Serializable
public data class ResponseMetadata(
    public val statusCode: Int,
    public val headers: Map<String, String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val cookies: List<String>? = null,
) {
    public companion object {
        /** The one header treated as repeatable rather than joined; routed to [cookies]. */
        public const val SET_COOKIE: String = "Set-Cookie"

        /**
         * Builds metadata from multi-valued headers, as produced by most HTTP server models.
         *
         * Each header's values are joined with `", "` into the single string the prelude format
         * requires. `Set-Cookie` entries (matched case-insensitively, across every spelling
         * present in [headers]) are collected into [cookies] instead of being joined, and are
         * absent from [ResponseMetadata.headers]. When no cookies are present, [cookies] is null
         * and the field does not appear in the encoded prelude.
         */
        public fun fromMultiValue(
            statusCode: Int,
            headers: Map<String, List<String>>,
        ): ResponseMetadata {
            val cookies = headers.entries
                .filter { it.key.equals(SET_COOKIE, ignoreCase = true) }
                .flatMap { it.value }
            val joined = headers
                .filterKeys { !it.equals(SET_COOKIE, ignoreCase = true) }
                .mapValues { (_, values) -> values.joinToString(HEADER_VALUE_SEPARATOR) }
            return ResponseMetadata(
                statusCode = statusCode,
                headers = joined,
                cookies = cookies.ifEmpty { null },
            )
        }
    }
}
