package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import nl.vintik.streaming.java.ValidationResult.Invalid;
import nl.vintik.streaming.java.ValidationResult.Reason;
import nl.vintik.streaming.java.ValidationResult.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Example-based unit tests for {@link FileNameValidator} (Req 3.1&ndash;3.6).
 *
 * <p>These cover each rejection {@link Reason} and representative acceptances as explicit
 * examples, complementing the Property 4 test (task 7.2). The validator applies its checks in a
 * most-specific-first order, so several cases below deliberately pin which reason wins when more
 * than one rule could match &mdash; most importantly, an absolute-path prefix is reported ahead of
 * the separator and allow-list checks even when the name also violates those.
 *
 * <p>{@link ValidationResult} is unwrapped through {@code instanceof} pattern matching so a
 * wrong-variant outcome fails the test rather than throwing a {@code ClassCastException}.
 */
class FileNameValidatorTest {

    private final FileNameValidator validator = new FileNameValidator();

    /** Validates and returns the rejection reason, failing the test if the name was accepted. */
    private Reason reasonOf(String fileName) {
        ValidationResult result = validator.validate(fileName);
        if (result instanceof Invalid invalid) {
            return invalid.reason();
        }
        return fail("expected Invalid for [" + fileName + "] but got " + result);
    }

    /** Asserts the name is accepted and returned unchanged, failing if it was rejected. */
    private void assertAcceptedUnchanged(String fileName) {
        ValidationResult result = validator.validate(fileName);
        if (result instanceof Valid valid) {
            assertEquals(fileName, valid.fileName(), "a valid name must be returned unchanged");
            return;
        }
        fail("expected Valid for [" + fileName + "] but got " + result);
    }

    // --- Acceptances (Req 3.1) ---

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "report.pdf",
                "my-file_v2.txt",
                "a",
                "FILE123",
                "data.tar.gz",
                "_hidden",
                "image-2024_final.PNG",
                "0",
                "-",
                "A.B-C_D.9",
            })
    @DisplayName("Given an allow-list-only file name When validated Then it is accepted with the same name")
    void allowListOnlyNamesAreAccepted(String fileName) {
        assertAcceptedUnchanged(fileName);
    }

    @Test
    @DisplayName("Given a 1024-character name When validated Then it is accepted at the length boundary")
    void nameAtLengthBoundaryIsAccepted() {
        assertAcceptedUnchanged("a".repeat(1024));
    }

    // --- MISSING (Req 3.5) ---

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", " \t \n "})
    @DisplayName("Given a null, empty, or all-whitespace file name When validated Then it is rejected as MISSING")
    void blankNamesAreRejectedAsMissing(String fileName) {
        assertEquals(Reason.MISSING, reasonOf(fileName));
    }

    // --- TOO_LONG (Req 3.6) ---

    @Test
    @DisplayName("Given a 1025-character name When validated Then it is rejected as TOO_LONG")
    void overLengthNameIsRejectedAsTooLong() {
        assertEquals(Reason.TOO_LONG, reasonOf("a".repeat(1025)));
    }

    @Test
    @DisplayName("Given an over-length name that also contains a separator When validated Then TOO_LONG wins over PATH_SEPARATOR")
    void tooLongIsReportedAheadOfPathSeparator() {
        // the length check runs before the separator check
        assertEquals(Reason.TOO_LONG, reasonOf("a/".repeat(700)));
    }

    // --- ABSOLUTE_PATH (Req 3.4) ---

    @Test
    @DisplayName("Given a leading forward slash When validated Then it is rejected as ABSOLUTE_PATH")
    void leadingForwardSlashIsRejectedAsAbsolutePath() {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("/etc/passwd"));
    }

    @Test
    @DisplayName("Given a leading backslash When validated Then it is rejected as ABSOLUTE_PATH")
    void leadingBackslashIsRejectedAsAbsolutePath() {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("\\windows\\system32"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"C:file.txt", "c:data", "Z:report.pdf"})
    @DisplayName("Given a drive-letter prefix When validated Then it is rejected as ABSOLUTE_PATH")
    void driveLetterPrefixIsRejectedAsAbsolutePath(String fileName) {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf(fileName));
    }

    @Test
    @DisplayName("Given a leading slash with a separator and an illegal character When validated Then ABSOLUTE_PATH wins")
    void absolutePathIsReportedAheadOfSeparatorAndIllegalCharacter() {
        // "/a*b" is an absolute prefix AND contains a separator AND an illegal '*';
        // the absolute-prefix check runs first, so ABSOLUTE_PATH is the reported reason
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("/a*b"));
    }

    @Test
    @DisplayName("Given a drive-letter prefix with a backslash and an illegal character When validated Then ABSOLUTE_PATH wins")
    void driveLetterPrefixIsReportedAheadOfSeparatorAndIllegalCharacter() {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("C:\\bad*"));
    }

    // --- PATH_SEPARATOR (Req 3.2) ---

    @Test
    @DisplayName("Given an embedded forward slash When validated Then it is rejected as PATH_SEPARATOR")
    void embeddedForwardSlashIsRejectedAsPathSeparator() {
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("dir/file.txt"));
    }

    @Test
    @DisplayName("Given an embedded backslash When validated Then it is rejected as PATH_SEPARATOR")
    void embeddedBackslashIsRejectedAsPathSeparator() {
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("dir\\file.txt"));
    }

    @Test
    @DisplayName("Given a parent-directory traversal with separators When validated Then PATH_SEPARATOR wins over PARENT_DIR")
    void pathSeparatorIsReportedAheadOfParentDir() {
        // the separator check runs before the parent-dir check
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("../secret"));
    }

    @Test
    @DisplayName("Given a separator alongside an illegal character When validated Then PATH_SEPARATOR wins over ILLEGAL_CHARACTER")
    void pathSeparatorIsReportedAheadOfIllegalCharacter() {
        // "dir/na me" has both a separator and a space; the separator check runs first
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("dir/na me"));
    }

    // --- PARENT_DIR (Req 3.3) ---

    @Test
    @DisplayName("Given a bare parent-directory sequence When validated Then it is rejected as PARENT_DIR")
    void bareParentDirectoryIsRejectedAsParentDir() {
        assertEquals(Reason.PARENT_DIR, reasonOf(".."));
    }

    @Test
    @DisplayName("Given an embedded double-period When validated Then PARENT_DIR wins over the allow-list")
    void parentDirIsReportedAheadOfAllowList() {
        // ".." is made only of allow-listed characters, so the explicit parent-dir check must run first
        assertEquals(Reason.PARENT_DIR, reasonOf("file..txt"));
    }

    // --- ILLEGAL_CHARACTER (Req 3.1) ---

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "file name.txt",
                "file@home",
                "caf\u00e9.txt",
                "na\u00efve",
                "100%",
                "a+b",
                "tab\there",
                "semi;colon",
            })
    @DisplayName("Given a character outside the allow-list When validated Then it is rejected as ILLEGAL_CHARACTER")
    void charactersOutsideAllowListAreRejectedAsIllegal(String fileName) {
        assertEquals(Reason.ILLEGAL_CHARACTER, reasonOf(fileName));
    }

    @Test
    @DisplayName("Given a colon that is not a drive-letter prefix When validated Then it is rejected as ILLEGAL_CHARACTER")
    void nonDrivePrefixColonIsRejectedAsIllegal() {
        // more than one leading character before ':' means it is not an absolute prefix, and there
        // is no separator, so the allow-list check is the first to reject the ':'
        assertEquals(Reason.ILLEGAL_CHARACTER, reasonOf("ab:cd"));
    }
}
