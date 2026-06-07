package com.example.streaming

import kotlinx.serialization.Serializable

/**
 * Metadata document serialized as segment 1 of the API Gateway streaming response
 * protocol (metadata JSON → 8 null bytes → body).
 *
 * Headers are modeled as name → list of values so repeatable headers (e.g.
 * `Set-Cookie`) and empty-valued headers are representable. Serialized with
 * kotlinx-serialization and must round-trip: `decode(encode(m)) == m`.
 */
@Serializable
data class ResponseMetadata(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
)
