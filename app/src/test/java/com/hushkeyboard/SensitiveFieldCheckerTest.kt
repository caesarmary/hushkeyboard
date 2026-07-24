package com.hushkeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SensitiveFieldChecker.isSensitive() and isPasswordField().
 *
 * isSensitive  — guards autocorrect suppression (passwords + no-suggestions fields).
 * isPasswordField — guards getTextBeforeCursor in long-press backspace (passwords only).
 *
 * Each test passes a raw inputType integer and asserts the expected result.
 * The integer values are taken directly from android.text.InputType constants.
 * Using the raw integers here (rather than the Android constants) keeps these
 * tests runnable on the plain JVM without any Android runtime.
 *
 * See SECURITY.md rule 4 (password-field discipline) for why these checks matter.
 */
class SensitiveFieldCheckerTest {

    // -------------------------------------------------------------------------
    // Fields that must be treated as sensitive
    // -------------------------------------------------------------------------

    @Test
    fun `password variation is sensitive`() {
        // TYPE_CLASS_TEXT (0x00000001) | TYPE_TEXT_VARIATION_PASSWORD (0x00000080)
        val inputType = 0x00000001 or 0x00000080
        assertTrue(SensitiveFieldChecker.isSensitive(inputType))
    }

    @Test
    fun `visible password variation is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD (0x00000090)
        val inputType = 0x00000001 or 0x00000090
        assertTrue(SensitiveFieldChecker.isSensitive(inputType))
    }

    @Test
    fun `web password variation is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD (0x000000E0)
        val inputType = 0x00000001 or 0x000000E0
        assertTrue(SensitiveFieldChecker.isSensitive(inputType))
    }

    @Test
    fun `number password variation is sensitive`() {
        // TYPE_CLASS_NUMBER (0x00000002) | TYPE_NUMBER_VARIATION_PASSWORD (0x00000010)
        val inputType = 0x00000002 or 0x00000010
        assertTrue(SensitiveFieldChecker.isSensitive(inputType))
    }

    @Test
    fun `no-suggestions flag is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS (0x00080000)
        val inputType = 0x00000001 or 0x00080000
        assertTrue(SensitiveFieldChecker.isSensitive(inputType))
    }

    // -------------------------------------------------------------------------
    // Fields that must NOT be treated as sensitive
    // -------------------------------------------------------------------------

    @Test
    fun `normal text field is not sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL (0x00000000)
        val inputType = 0x00000001
        assertFalse(SensitiveFieldChecker.isSensitive(inputType))
    }

    @Test
    fun `zero inputType is not sensitive`() {
        // TYPE_NULL — field type not set; treat as safe
        assertFalse(SensitiveFieldChecker.isSensitive(0))
    }

    // -------------------------------------------------------------------------
    // isPasswordField — password variations only, NOT no-suggestions
    // -------------------------------------------------------------------------

    @Test
    fun `isPasswordField password variation is true`() {
        val inputType = 0x00000001 or 0x00000080
        assertTrue(SensitiveFieldChecker.isPasswordField(inputType))
    }

    @Test
    fun `isPasswordField visible password variation is true`() {
        val inputType = 0x00000001 or 0x00000090
        assertTrue(SensitiveFieldChecker.isPasswordField(inputType))
    }

    @Test
    fun `isPasswordField web password variation is true`() {
        val inputType = 0x00000001 or 0x000000E0
        assertTrue(SensitiveFieldChecker.isPasswordField(inputType))
    }

    @Test
    fun `isPasswordField number password variation is true`() {
        val inputType = 0x00000002 or 0x00000010
        assertTrue(SensitiveFieldChecker.isPasswordField(inputType))
    }

    @Test
    fun `isPasswordField no-suggestions flag is NOT a password field`() {
        // Google Search and similar fields set TYPE_TEXT_FLAG_NO_SUGGESTIONS.
        // Autocorrect is suppressed there (isSensitive returns true), but reading
        // text before the cursor for word-boundary deletion is safe — the fix for
        // the long-press backspace regression in Google Search.
        val inputType = 0x00000001 or 0x00080000
        assertFalse(SensitiveFieldChecker.isPasswordField(inputType))
    }

    @Test
    fun `isPasswordField normal text field is false`() {
        assertFalse(SensitiveFieldChecker.isPasswordField(0x00000001))
    }

    @Test
    fun `isPasswordField zero inputType is false`() {
        assertFalse(SensitiveFieldChecker.isPasswordField(0))
    }
}
