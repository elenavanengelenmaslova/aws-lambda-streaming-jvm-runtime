package nl.vintik.lambda.streaming

/**
 * Outcome of parsing the API Gateway proxy event from the input stream.
 *
 * Drives the handler's status mapping deterministically: [Parsed] carries the
 * domain request; [ParseError] maps to HTTP 400 (request could not be parsed).
 */
sealed interface ParseResult {
    data class Parsed(val request: StreamRequest) : ParseResult
    data object ParseError : ParseResult
}
