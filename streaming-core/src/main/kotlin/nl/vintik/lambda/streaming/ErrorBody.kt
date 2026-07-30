package nl.vintik.lambda.streaming

import kotlinx.serialization.Serializable

/**
 * A short JSON message written as the body of an error response (400/404/502).
 * No file bytes are written for error responses.
 */
@Serializable
public data class ErrorBody(public val message: String)
