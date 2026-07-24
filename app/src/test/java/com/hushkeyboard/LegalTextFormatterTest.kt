package com.hushkeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextFormatterTest {

    @Test
    fun allCapsLine_isHeader() {
        assertTrue(LegalTextFormatter.isHeaderLine("WHAT WE COLLECT"))
    }

    @Test
    fun allCapsLineWithPunctuation_isHeader() {
        assertTrue(LegalTextFormatter.isHeaderLine("SMOLLM2-135M-INSTRUCT"))
    }

    @Test
    fun mixedCaseLine_isNotHeader() {
        assertFalse(LegalTextFormatter.isHeaderLine("Privacy Policy for hushkeyboard"))
    }

    @Test
    fun blankLine_isNotHeader() {
        assertFalse(LegalTextFormatter.isHeaderLine(""))
    }

    @Test
    fun shortAllCapsLine_isNotHeader() {
        assertFalse(LegalTextFormatter.isHeaderLine("OK"))
    }

    @Test
    fun lineWithNoLetters_isNotHeader() {
        assertFalse(LegalTextFormatter.isHeaderLine("12345 -- 000"))
    }
}
