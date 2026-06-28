package nl.vintik.lambda.streaming

/**
 * Outcome of confirming the requested S3 object's existence and size before the
 * response status is committed.
 *
 * [Exists] carries the object size (used for `Content-Length`); [NotFound] maps
 * to HTTP 404; [Failure] (any non-missing error or timeout) maps to HTTP 502.
 */
sealed interface HeadResult {
    data class Exists(val size: Long) : HeadResult
    data object NotFound : HeadResult
    data class Failure(val cause: Throwable) : HeadResult
}
