package com.example.streaming

import kotlinx.serialization.Serializable

/**
 * Metadata document serialized as segment 1 of the API Gateway streaming response
 * protocol (metadata JSON → 8 null bytes → body).
 *
 * Headers are modeled as name → single string value, matching the format
 * API Gateway expects for the streaming protocol metadata prelude.
 * For repeatable headers (e.g. `Set-Cookie`), values are joined with ", ".
 */
@Serializable
data class ResponseMetadata(
    val statusCode: Int,
    val headers: Map<String, String>,
)
