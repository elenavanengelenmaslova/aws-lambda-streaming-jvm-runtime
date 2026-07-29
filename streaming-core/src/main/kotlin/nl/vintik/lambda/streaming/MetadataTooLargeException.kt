package nl.vintik.lambda.streaming

/**
 * Raised by [ResponseWriter] when a serialized metadata prelude exceeds the `maxPreludeLen` the
 * writer was configured with.
 *
 * Thrown before any byte is written, so the output stream is untouched and the response status
 * is still uncommitted — the caller is free to write a different response instead.
 *
 * Only reachable when a limit was opted into; a writer built without one never raises this.
 */
public class MetadataTooLargeException(message: String) : RuntimeException(message)
