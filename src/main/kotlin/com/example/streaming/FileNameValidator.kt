package com.example.streaming

import com.example.streaming.ValidationResult.Reason

/**
 * Validates a requested file name before any S3 request is issued, so clients
 * cannot traverse paths or reach arbitrary objects (Req 2).
 *
 * [validate] is a pure function with no I/O. The allow-list (Req 2.1) is the
 * primary guard — it already excludes `/`, `\`, and `:` — but the separator,
 * parent-directory, and absolute-prefix checks run first to produce a specific
 * [Reason] for each rejection. Checks are ordered so the earliest applicable,
 * most specific reason is returned:
 *
 *  1. [Reason.MISSING]        — null, empty, or all-whitespace (Req 2.5, 1.2)
 *  2. [Reason.TOO_LONG]       — length > 1024 (Req 2.6, 1.4)
 *  3. [Reason.ABSOLUTE_PATH]  — leading `/`, leading `\`, or drive-letter `X:` (Req 2.4)
 *  4. [Reason.PATH_SEPARATOR] — any `/` or `\` (Req 2.2)
 *  5. [Reason.PARENT_DIR]     — a `..` sequence (Req 2.3)
 *  6. [Reason.ILLEGAL_CHARACTER] — any character outside the allow-list (Req 2.1)
 */
class FileNameValidator {

    fun validate(fileName: String?): ValidationResult {
        if (fileName.isNullOrBlank()) return ValidationResult.Invalid(Reason.MISSING)
        if (fileName.length > MAX_LENGTH) return ValidationResult.Invalid(Reason.TOO_LONG)
        if (hasAbsolutePrefix(fileName)) return ValidationResult.Invalid(Reason.ABSOLUTE_PATH)
        if (fileName.any { it == '/' || it == '\\' }) return ValidationResult.Invalid(Reason.PATH_SEPARATOR)
        if (fileName.contains("..")) return ValidationResult.Invalid(Reason.PARENT_DIR)
        if (!fileName.all(::isAllowed)) return ValidationResult.Invalid(Reason.ILLEGAL_CHARACTER)
        return ValidationResult.Valid(fileName)
    }

    private fun hasAbsolutePrefix(fileName: String): Boolean =
        fileName.startsWith('/') ||
            fileName.startsWith('\\') ||
            (fileName.length >= 2 && isAsciiLetter(fileName[0]) && fileName[1] == ':')

    private fun isAllowed(c: Char): Boolean =
        isAsciiLetter(c) || c in '0'..'9' || c == '-' || c == '_' || c == '.'

    private fun isAsciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

    private companion object {
        const val MAX_LENGTH = 1024
    }
}
