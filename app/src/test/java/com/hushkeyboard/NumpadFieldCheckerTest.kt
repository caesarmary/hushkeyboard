package com.hushkeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NumpadFieldChecker.isNumericField().
 *
 * The function must return true for TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, and
 * TYPE_CLASS_DATETIME (showing the numpad layout), and must return false for every
 * other case — including password variations of numeric types (which keep the full
 * QWERTY keyboard with autocorrect suppressed, per SECURITY.md rule 4).
 *
 * Raw integer values are used so these tests run on the plain JVM without any
 * Android runtime, matching the pattern established in SensitiveFieldCheckerTest.
 */
class NumpadFieldCheckerTest {

    // -------------------------------------------------------------------------
    // Fields that must show the numpad
    // -------------------------------------------------------------------------

    @Test
    fun `TYPE_CLASS_NUMBER plain shows numpad`() {
        // TYPE_CLASS_NUMBER = 0x00000002, no variation
        assertTrue(NumpadFieldChecker.isNumericField(0x00000002))
    }

    @Test
    fun `TYPE_CLASS_NUMBER with signed variation shows numpad`() {
        // TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_NORMAL (0x00000000) — no variation set
        // Using TYPE_NUMBER_FLAG_SIGNED (0x00001000) — a flag, not a variation, should still show numpad
        assertTrue(NumpadFieldChecker.isNumericField(0x00000002 or 0x00001000))
    }

    @Test
    fun `TYPE_CLASS_NUMBER with decimal variation shows numpad`() {
        // TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_DECIMAL (0x00002000)
        assertTrue(NumpadFieldChecker.isNumericField(0x00000002 or 0x00002000))
    }

    @Test
    fun `TYPE_CLASS_PHONE shows numpad`() {
        // TYPE_CLASS_PHONE = 0x00000003
        assertTrue(NumpadFieldChecker.isNumericField(0x00000003))
    }

    @Test
    fun `TYPE_CLASS_DATETIME shows numpad`() {
        // TYPE_CLASS_DATETIME = 0x00000004
        assertTrue(NumpadFieldChecker.isNumericField(0x00000004))
    }

    @Test
    fun `TYPE_CLASS_DATETIME with date variation shows numpad`() {
        // TYPE_CLASS_DATETIME | TYPE_DATETIME_VARIATION_DATE (0x00000010)
        // Note: 0x10 is also TYPE_NUMBER_VARIATION_PASSWORD for CLASS_NUMBER, but for
        // CLASS_DATETIME the same bit means a date picker — not a password. This test
        // confirms we only block the password variation for CLASS_NUMBER.
        assertTrue(NumpadFieldChecker.isNumericField(0x00000004 or 0x00000010))
    }

    @Test
    fun `TYPE_CLASS_DATETIME with time variation shows numpad`() {
        // TYPE_CLASS_DATETIME | TYPE_DATETIME_VARIATION_TIME (0x00000020)
        assertTrue(NumpadFieldChecker.isNumericField(0x00000004 or 0x00000020))
    }

    // -------------------------------------------------------------------------
    // Fields that must NOT show the numpad — password variations
    // -------------------------------------------------------------------------

    @Test
    fun `TYPE_CLASS_NUMBER with password variation does NOT show numpad`() {
        // TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD (0x00000010)
        // SECURITY.md rule 4: password fields keep the full keyboard.
        assertFalse(NumpadFieldChecker.isNumericField(0x00000002 or 0x00000010))
    }

    // -------------------------------------------------------------------------
    // Fields that must NOT show the numpad — non-numeric classes
    // -------------------------------------------------------------------------

    @Test
    fun `TYPE_CLASS_TEXT does NOT show numpad`() {
        // TYPE_CLASS_TEXT = 0x00000001
        assertFalse(NumpadFieldChecker.isNumericField(0x00000001))
    }

    @Test
    fun `TYPE_CLASS_TEXT with password variation does NOT show numpad`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD (0x00000080)
        assertFalse(NumpadFieldChecker.isNumericField(0x00000001 or 0x00000080))
    }

    @Test
    fun `zero inputType does NOT show numpad`() {
        // TYPE_NULL — no class declared
        assertFalse(NumpadFieldChecker.isNumericField(0))
    }

    @Test
    fun `TYPE_CLASS_TEXT with no-suggestions flag does NOT show numpad`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS (0x00080000)
        assertFalse(NumpadFieldChecker.isNumericField(0x00000001 or 0x00080000))
    }
}
