package com.example.streaming

import com.example.streaming.ValidationResult.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Example-based unit tests for [FileNameValidator] (Req 2.1–2.6).
 *
 * These cover each rejection [Reason] and representative acceptances as explicit
 * examples, complementing the Property 4 test (task 8.2). Rule order is
 * most-specific-first, so several cases below deliberately pin which reason wins
 * when more than one rule could match.
 */
class FileNameValidatorTest {

    private val validator = FileNameValidator()

    private fun reasonOf(fileName: String?): Reason {
        val result = validator.validate(fileName)
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        return invalid.reason
    }

    // --- Acceptances (Req 2.1) ---

    @ParameterizedTest
    @ValueSource(
        strings = [
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
        ],
    )
    fun `Given an allow-list-only file name When validated Then it is accepted with the same name`(fileName: String) {
        val result = validator.validate(fileName)

        val valid = assertInstanceOf(ValidationResult.Valid::class.java, result)
        assertEquals(fileName, valid.fileName)
    }

    @Test
    fun `Given a 1024-character name When validated Then it is accepted at the length boundary`() {
        val fileName = "a".repeat(1024)

        val result = validator.validate(fileName)

        val valid = assertInstanceOf(ValidationResult.Valid::class.java, result)
        assertEquals(fileName, valid.fileName)
    }

    // --- MISSING (Req 2.5, 1.2) ---

    @Test
    fun `Given a null file name When validated Then it is rejected as MISSING`() {
        assertEquals(Reason.MISSING, reasonOf(null))
    }

    @Test
    fun `Given an empty file name When validated Then it is rejected as MISSING`() {
        assertEquals(Reason.MISSING, reasonOf(""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["   ", "\t", "\n", " \t \n "])
    fun `Given an all-whitespace file name When validated Then it is rejected as MISSING`(fileName: String) {
        assertEquals(Reason.MISSING, reasonOf(fileName))
    }

    // --- TOO_LONG (Req 2.6, 1.4) ---

    @Test
    fun `Given a 1025-character name When validated Then it is rejected as TOO_LONG`() {
        assertEquals(Reason.TOO_LONG, reasonOf("a".repeat(1025)))
    }

    @Test
    fun `Given an over-length name that also contains a separator When validated Then TOO_LONG wins over PATH_SEPARATOR`() {
        // length check runs before the separator check
        assertEquals(Reason.TOO_LONG, reasonOf("a/".repeat(700)))
    }

    // --- ABSOLUTE_PATH (Req 2.4) ---

    @Test
    fun `Given a leading forward slash When validated Then it is rejected as ABSOLUTE_PATH`() {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("/etc/passwd"))
    }

    @Test
    fun `Given a leading backslash When validated Then it is rejected as ABSOLUTE_PATH`() {
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf("\\windows\\system32"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["C:file.txt", "c:data", "Z:report.pdf"])
    fun `Given a drive-letter prefix When validated Then it is rejected as ABSOLUTE_PATH`(fileName: String) {
        // absolute-prefix check runs before the separator check, so X: wins
        assertEquals(Reason.ABSOLUTE_PATH, reasonOf(fileName))
    }

    // --- PATH_SEPARATOR (Req 2.2) ---

    @Test
    fun `Given an embedded forward slash When validated Then it is rejected as PATH_SEPARATOR`() {
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("dir/file.txt"))
    }

    @Test
    fun `Given an embedded backslash When validated Then it is rejected as PATH_SEPARATOR`() {
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("dir\\file.txt"))
    }

    @Test
    fun `Given a parent-directory traversal with separators When validated Then PATH_SEPARATOR wins over PARENT_DIR`() {
        // separator check runs before the parent-dir check
        assertEquals(Reason.PATH_SEPARATOR, reasonOf("../secret"))
    }

    // --- PARENT_DIR (Req 2.3) ---

    @Test
    fun `Given a bare parent-directory sequence When validated Then it is rejected as PARENT_DIR`() {
        assertEquals(Reason.PARENT_DIR, reasonOf(".."))
    }

    @Test
    fun `Given an embedded double-period When validated Then PARENT_DIR wins over the allow-list`() {
        // ".." is made of allow-listed chars, so the explicit parent-dir check must run first
        assertEquals(Reason.PARENT_DIR, reasonOf("file..txt"))
    }

    // --- ILLEGAL_CHARACTER (Req 2.1) ---

    @ParameterizedTest
    @ValueSource(
        strings = [
            "file name.txt",
            "file@home",
            "caf\u00e9.txt",
            "na\u00efve",
            "100%",
            "a+b",
            "tab\there",
            "semi;colon",
        ],
    )
    fun `Given a character outside the allow-list When validated Then it is rejected as ILLEGAL_CHARACTER`(fileName: String) {
        assertEquals(Reason.ILLEGAL_CHARACTER, reasonOf(fileName))
    }

    @Test
    fun `Given a colon that is not a drive-letter prefix When validated Then it is rejected as ILLEGAL_CHARACTER`() {
        // no absolute prefix (more than one leading char before ':') and no separator
        assertEquals(Reason.ILLEGAL_CHARACTER, reasonOf("ab:cd"))
    }

    @Test
    fun `Given a valid file name When validated Then there is no rejection reason`() {
        assertNull((validator.validate("ok.txt") as? ValidationResult.Invalid)?.reason)
    }
}
