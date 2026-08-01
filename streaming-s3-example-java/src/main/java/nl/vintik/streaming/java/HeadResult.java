package nl.vintik.streaming.java;

/**
 * Outcome of confirming the requested S3 object's existence and size before the
 * response status is committed.
 *
 * <p>{@link Exists} carries the object size (used for {@code Content-Length});
 * {@link NotFound} maps to HTTP 404; {@link Failure} (any non-missing error or timeout)
 * maps to HTTP 502.
 */
public sealed interface HeadResult permits HeadResult.Exists, HeadResult.NotFound, HeadResult.Failure {

    /**
     * The object exists with a known content length.
     *
     * @param size non-negative content length in bytes, used for {@code Content-Length}
     */
    record Exists(long size) implements HeadResult {
        /**
         * Bug-detection guard mirroring the Kotlin {@code require(size >= 0)}: a negative
         * size is never a valid content length, so it signals a programming error rather
         * than bad user input.
         */
        public Exists {
            if (size < 0) {
                throw new IllegalArgumentException("size must be >= 0 but was " + size);
            }
        }
    }

    /** The requested object does not exist (S3 {@code NoSuchKey}). */
    record NotFound() implements HeadResult {}

    /** Existence/size retrieval failed for a non-missing reason (error or timeout). */
    record Failure(Throwable cause) implements HeadResult {}
}
