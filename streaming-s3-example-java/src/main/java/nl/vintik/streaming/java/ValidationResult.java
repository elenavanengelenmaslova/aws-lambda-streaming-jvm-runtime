package nl.vintik.streaming.java;

/**
 * Outcome of validating a requested file name before any S3 request is issued.
 *
 * <p>{@link Valid} carries the accepted file name; {@link Invalid} carries a specific
 * {@link Reason} so the handler can map every rejection to HTTP 400 with a clear cause.
 */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

    /** The file name passed every check and is safe to address in S3. */
    record Valid(String fileName) implements ValidationResult {}

    /** The file name was rejected; {@code reason} identifies the first failing check. */
    record Invalid(Reason reason) implements ValidationResult {}

    /**
     * The specific reason a file name was rejected, in the validator's evaluation order:
     * {@code MISSING} &rarr; {@code TOO_LONG} &rarr; {@code ABSOLUTE_PATH} &rarr;
     * {@code PATH_SEPARATOR} &rarr; {@code PARENT_DIR} &rarr; {@code ILLEGAL_CHARACTER}.
     */
    enum Reason { MISSING, TOO_LONG, ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH }
}
