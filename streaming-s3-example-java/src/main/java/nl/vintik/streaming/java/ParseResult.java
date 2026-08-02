package nl.vintik.streaming.java;

/**
 * Outcome of parsing the API Gateway proxy event from the input stream.
 *
 * <p>Drives the handler's status mapping deterministically via a pattern-matching
 * {@code switch}: {@link Parsed} carries the domain request; {@link ParseError} maps to
 * HTTP 400 (request could not be parsed).
 */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.ParseError {

    /** The proxy event was decoded successfully into a {@link StreamRequest}. */
    record Parsed(StreamRequest request) implements ParseResult {}

    /** The proxy event could not be decoded (malformed, empty, or truncated JSON). */
    record ParseError() implements ParseResult {}
}
