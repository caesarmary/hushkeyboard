package com.hushkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SymbolStateManagerTest {

    private lateinit var sm: SymbolStateManager

    @Before
    fun setUp() {
        sm = SymbolStateManager()
    }

    // --- Initial state ---

    @Test
    fun `initial state is LETTERS`() {
        assertEquals(SymbolState.LETTERS, sm.state)
        assertTrue(sm.isShowingLetters)
    }

    // --- onSymbolsKeyTapped ---

    @Test
    fun `LETTERS plus symbols key goes to SYMBOLS_1`() {
        sm.onSymbolsKeyTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
        assertFalse(sm.isShowingLetters)
    }

    @Test
    fun `calling onSymbolsKeyTapped again while in SYMBOLS_1 stays in SYMBOLS_1`() {
        sm.onSymbolsKeyTapped()
        sm.onSymbolsKeyTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
    }

    // --- onLettersKeyTapped ---

    @Test
    fun `SYMBOLS_1 plus letters key returns to LETTERS`() {
        sm.onSymbolsKeyTapped()
        sm.onLettersKeyTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
        assertTrue(sm.isShowingLetters)
    }

    @Test
    fun `SYMBOLS_2 plus letters key returns to LETTERS`() {
        sm.onSymbolsKeyTapped()
        sm.onPageTwoTapped()
        sm.onLettersKeyTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
        assertTrue(sm.isShowingLetters)
    }

    @Test
    fun `onLettersKeyTapped while already in LETTERS stays in LETTERS`() {
        sm.onLettersKeyTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    // --- onPageTwoTapped ---

    @Test
    fun `SYMBOLS_1 plus page two key goes to SYMBOLS_2`() {
        sm.onSymbolsKeyTapped()
        sm.onPageTwoTapped()
        assertEquals(SymbolState.SYMBOLS_2, sm.state)
        assertFalse(sm.isShowingLetters)
    }

    @Test
    fun `onPageTwoTapped while in LETTERS has no effect`() {
        sm.onPageTwoTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    @Test
    fun `onPageTwoTapped while in SYMBOLS_2 has no effect`() {
        sm.onSymbolsKeyTapped()
        sm.onPageTwoTapped()
        sm.onPageTwoTapped()
        assertEquals(SymbolState.SYMBOLS_2, sm.state)
    }

    // --- onPageOneTapped ---

    @Test
    fun `SYMBOLS_2 plus page one key returns to SYMBOLS_1`() {
        sm.onSymbolsKeyTapped()
        sm.onPageTwoTapped()
        sm.onPageOneTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
    }

    @Test
    fun `onPageOneTapped while in LETTERS has no effect`() {
        sm.onPageOneTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    @Test
    fun `onPageOneTapped while in SYMBOLS_1 has no effect`() {
        sm.onSymbolsKeyTapped()
        sm.onPageOneTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
    }

    // --- Full page round-trip ---

    @Test
    fun `full round trip LETTERS to SYMBOLS_1 to SYMBOLS_2 and back to LETTERS`() {
        sm.onSymbolsKeyTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
        sm.onPageTwoTapped()
        assertEquals(SymbolState.SYMBOLS_2, sm.state)
        sm.onPageOneTapped()
        assertEquals(SymbolState.SYMBOLS_1, sm.state)
        sm.onLettersKeyTapped()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    // --- onFieldChange ---

    @Test
    fun `onFieldChange from SYMBOLS_1 resets to LETTERS`() {
        sm.onSymbolsKeyTapped()
        sm.onFieldChange()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    @Test
    fun `onFieldChange from SYMBOLS_2 resets to LETTERS`() {
        sm.onSymbolsKeyTapped()
        sm.onPageTwoTapped()
        sm.onFieldChange()
        assertEquals(SymbolState.LETTERS, sm.state)
    }

    @Test
    fun `onFieldChange from LETTERS stays in LETTERS`() {
        sm.onFieldChange()
        assertEquals(SymbolState.LETTERS, sm.state)
    }
}
