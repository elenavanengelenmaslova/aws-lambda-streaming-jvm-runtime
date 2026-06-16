package com.example.streaming

/**
 * Outcome of validating a requested file name before any S3 request is issued.
 *
 * [Valid] carries the accepted file name; [Invalid] carries a specific [Reason]
 * so the handler can map every rejection to HTTP 400 with a clear cause.
 */
sealed interface ValidationResult {
    data class Valid(val fileName: String) : ValidationResult
    data class Invalid(val reason: Reason) : ValidationResult

    enum class Reason { MISSING, TOO_LONG, ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH }
}
