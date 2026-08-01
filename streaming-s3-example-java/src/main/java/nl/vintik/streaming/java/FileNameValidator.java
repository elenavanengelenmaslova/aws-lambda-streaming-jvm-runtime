package nl.vintik.streaming.java;

import nl.vintik.streaming.java.ValidationResult.Invalid;
import nl.vintik.streaming.java.ValidationResult.Reason;
import nl.vintik.streaming.java.ValidationResult.Valid;

/**
 * Validates a requested file name before any S3 request is issued, so clients cannot
 * traverse paths or reach arbitrary objects (Req 3).
 *
 * <p>{@link #validate(String)} is a pure function with no I/O. The allow-list (Req 3.1)
 * is the primary guard &mdash; it already excludes {@code /}, {@code \}, and {@code :}
 * &mdash; but the separator, parent-directory, and absolute-prefix checks run first so
 * each rejection carries a specific {@link Reason}. Checks are ordered so the earliest
 * applicable, most specific reason is returned:
 *
 * <ol>
 *   <li>{@link Reason#MISSING} &mdash; null, empty, or all-whitespace (Req 3.5)</li>
 *   <li>{@link Reason#TOO_LONG} &mdash; length &gt; 1024 (Req 3.6)</li>
 *   <li>{@link Reason#ABSOLUTE_PATH} &mdash; leading {@code /}, leading {@code \},
 *       or drive-letter {@code X:} (Req 3.4)</li>
 *   <li>{@link Reason#PATH_SEPARATOR} &mdash; any {@code /} or {@code \} (Req 3.2)</li>
 *   <li>{@link Reason#PARENT_DIR} &mdash; a {@code ..} sequence (Req 3.3)</li>
 *   <li>{@link Reason#ILLEGAL_CHARACTER} &mdash; any character outside the
 *       allow-list (Req 3.1)</li>
 * </ol>
 *
 * <p>Java counterpart of the Kotlin example's {@code FileNameValidator}, preserving the
 * same allow-list and ordered rejection reasons.
 */
public final class FileNameValidator {

    private static final int MAX_LENGTH = 1024;

    /**
     * Applies the ordered checks and returns the outcome of the first failing check, or
     * {@link Valid} when every check passes.
     *
     * @param fileName the requested file name (may be null)
     * @return {@link Valid} with the accepted name, or {@link Invalid} with the reason
     */
    public ValidationResult validate(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new Invalid(Reason.MISSING);
        }
        if (fileName.length() > MAX_LENGTH) {
            return new Invalid(Reason.TOO_LONG);
        }
        if (hasAbsolutePrefix(fileName)) {
            return new Invalid(Reason.ABSOLUTE_PATH);
        }
        if (hasPathSeparator(fileName)) {
            return new Invalid(Reason.PATH_SEPARATOR);
        }
        if (fileName.contains("..")) {
            return new Invalid(Reason.PARENT_DIR);
        }
        if (!allCharactersAllowed(fileName)) {
            return new Invalid(Reason.ILLEGAL_CHARACTER);
        }
        return new Valid(fileName);
    }

    private static boolean hasAbsolutePrefix(String fileName) {
        return fileName.charAt(0) == '/'
            || fileName.charAt(0) == '\\'
            || (fileName.length() >= 2 && isAsciiLetter(fileName.charAt(0)) && fileName.charAt(1) == ':');
    }

    private static boolean hasPathSeparator(String fileName) {
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c == '/' || c == '\\') {
                return true;
            }
        }
        return false;
    }

    private static boolean allCharactersAllowed(String fileName) {
        for (int i = 0; i < fileName.length(); i++) {
            if (!isAllowed(fileName.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowed(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
