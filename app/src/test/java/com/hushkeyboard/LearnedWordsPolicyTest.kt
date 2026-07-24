package com.hushkeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LearnedWordsPolicy.isFieldExcludedFromLearning().
 *
 * SECURITY-CRITICAL (SECURITY.md rule 4): password-field content must NEVER be
 * recorded into the learned-words dictionary. Gate 8 forbids manual-only coverage
 * for a SECURITY.md invariant, so this logic is unit-tested here.
 *
 * Raw inputType integers are used (values from android.text.InputType) so the
 * tests run on the plain JVM without an Android runtime.
 */
class LearnedWordsPolicyTest {

    // ---- The non-negotiable invariant: password fields are excluded ----

    @Test
    fun `password variation is excluded from learning`() {
        val inputType = 0x00000001 or 0x00000080 // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    @Test
    fun `visible password variation is excluded from learning`() {
        val inputType = 0x00000001 or 0x00000090
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    @Test
    fun `web password variation is excluded from learning`() {
        val inputType = 0x00000001 or 0x000000E0
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    @Test
    fun `number password variation is excluded from learning`() {
        val inputType = 0x00000002 or 0x00000010
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    // ---- The stricter-than-required policy: no-suggestions fields are also excluded ----

    @Test
    fun `no-suggestions field is excluded from learning`() {
        // Search bars etc. set TYPE_TEXT_FLAG_NO_SUGGESTIONS. We do not persist
        // their content to disk even though rule 4 alone would not require this.
        val inputType = 0x00000001 or 0x00080000
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    // ---- Ordinary fields may be learned from ----

    @Test
    fun `normal text field is not excluded`() {
        assertFalse(LearnedWordsPolicy.isFieldExcludedFromLearning(0x00000001))
    }

    @Test
    fun `zero inputType is not excluded`() {
        // TYPE_NULL — a known, non-sensitive value. (The fail-safe for a *missing*
        // EditorInfo is the null-overload below.)
        assertFalse(LearnedWordsPolicy.isFieldExcludedFromLearning(0))
    }

    // ---- The null-EditorInfo fail-safe (Session 36 capture call site) ----

    @Test
    fun `null inputType is excluded (fail-safe)`() {
        // A null EditorInfo means there is no field to inspect; the keyboard treats
        // that as sensitive everywhere, so nothing is learned. SECURITY.md rule 4.
        val inputType: Int? = null
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    @Test
    fun `non-null normal inputType via the nullable overload is not excluded`() {
        // The nullable overload must still honour the underlying policy for a real value.
        val inputType: Int? = 0x00000001 // TYPE_CLASS_TEXT
        assertFalse(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }

    @Test
    fun `non-null password inputType via the nullable overload is excluded`() {
        val inputType: Int? = 0x00000001 or 0x00000080 // TYPE_CLASS_TEXT | password variation
        assertTrue(LearnedWordsPolicy.isFieldExcludedFromLearning(inputType))
    }
}
