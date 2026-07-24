package com.hushkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for InputStateManager.
 *
 * These tests directly cover the two bugs found during Phase 2 manual testing:
 *   M7 - autocorrect fired in a password field (tests 9 and 14)
 *   M8 - backspace + space re-expanded a deleted word (tests 8 and 13)
 *
 * All tests run on the plain JVM. No Android runtime required.
 */
class InputStateManagerTest {

    private lateinit var manager: InputStateManager

    @Before
    fun setUp() {
        manager = InputStateManager()
    }

    // -------------------------------------------------------------------------
    // onLetterTyped
    // -------------------------------------------------------------------------

    @Test
    fun `typing a letter in a normal field appends it to the buffer`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("hi", result)
    }

    @Test
    fun `typing a letter in a sensitive field does not append to the buffer`() {
        manager.onLetterTyped('h', isSensitive = true)
        manager.onLetterTyped('i', isSensitive = true)
        val result = manager.onWordCommit(isSensitive = true)
        assertNull(result)
    }

    @Test
    fun `typing a letter clears the suppression flag`() {
        // Type a word, backspace (sets suppressed), then type a new letter (clears suppressed).
        // The next commit should return the edited word, not null.
        manager.onLetterTyped('c', isSensitive = false)
        manager.onLetterTyped('a', isSensitive = false)
        manager.onLetterTyped('t', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        manager.onLetterTyped('r', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("car", result)
    }

    // -------------------------------------------------------------------------
    // onBackspace
    // -------------------------------------------------------------------------

    @Test
    fun `backspace removes the last character from the buffer`() {
        manager.onLetterTyped('c', isSensitive = false)
        manager.onLetterTyped('a', isSensitive = false)
        manager.onLetterTyped('t', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        // Suppressed now; type one more letter to clear suppression, then commit.
        manager.onLetterTyped('r', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("car", result)
    }

    @Test
    fun `backspace on an empty buffer does not crash`() {
        manager.onBackspace(isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `backspace in a sensitive field does not change the buffer`() {
        manager.onBackspace(isSensitive = true)
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // onWordCommit
    // -------------------------------------------------------------------------

    @Test
    fun `onWordCommit in a normal field returns the typed word`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('e', isSensitive = false)
        manager.onLetterTyped('l', isSensitive = false)
        manager.onLetterTyped('l', isSensitive = false)
        manager.onLetterTyped('o', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("hello", result)
    }

    @Test
    fun `onWordCommit clears the buffer so the next word starts fresh`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onWordCommit(isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `onWordCommit when suppressed returns null M8 regression`() {
        // M8: type a word, press backspace, then commit.
        // The commit must return null, not the partially-deleted word.
        manager.onLetterTyped('g', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onLetterTyped('r', isSensitive = false)
        manager.onLetterTyped('a', isSensitive = false)
        manager.onLetterTyped('f', isSensitive = false)
        manager.onLetterTyped('f', isSensitive = false)
        manager.onLetterTyped('e', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `onWordCommit in a sensitive field returns null M7 regression`() {
        // Even if the buffer has content, a sensitive-field commit must return null.
        manager.onLetterTyped('s', isSensitive = false)
        manager.onLetterTyped('e', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = true)
        assertNull(result)
    }

    @Test
    fun `onWordCommit with an empty buffer returns null`() {
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // onFieldChange
    // -------------------------------------------------------------------------

    @Test
    fun `onFieldChange clears the buffer`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onFieldChange()
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `onFieldChange clears the suppression flag`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        manager.onFieldChange()
        manager.onLetterTyped('o', isSensitive = false)
        manager.onLetterTyped('k', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("ok", result)
    }

    // -------------------------------------------------------------------------
    // M8 full sequence and recovery
    // -------------------------------------------------------------------------

    @Test
    fun `M8 full sequence type word backspace commit returns null`() {
        manager.onLetterTyped('g', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onLetterTyped('r', isSensitive = false)
        manager.onLetterTyped('a', isSensitive = false)
        manager.onLetterTyped('f', isSensitive = false)
        manager.onLetterTyped('f', isSensitive = false)
        manager.onLetterTyped('e', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `M8 recovery after backspace and new letter commit returns edited word`() {
        manager.onLetterTyped('c', isSensitive = false)
        manager.onLetterTyped('a', isSensitive = false)
        manager.onLetterTyped('t', isSensitive = false)
        manager.onBackspace(isSensitive = false)
        manager.onLetterTyped('r', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("car", result)
    }

    // -------------------------------------------------------------------------
    // onNonLetterTyped
    // -------------------------------------------------------------------------

    @Test
    fun `onNonLetterTyped clears the buffer`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onNonLetterTyped()
        assertTrue(manager.isWordEmpty)
    }

    @Test
    fun `onNonLetterTyped suppresses the next commit`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onNonLetterTyped()
        // Type new letters after the symbol to build a new word.
        manager.onLetterTyped('o', isSensitive = false)
        manager.onLetterTyped('k', isSensitive = false)
        // Suppression was set by onNonLetterTyped but cleared by onLetterTyped.
        // So the next commit should succeed.
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("ok", result)
    }

    @Test
    fun `onNonLetterTyped on empty buffer does not crash`() {
        manager.onNonLetterTyped()
        assertTrue(manager.isWordEmpty)
    }

    @Test
    fun `after onNonLetterTyped commit with no new letters returns null`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onNonLetterTyped()
        // Buffer is empty, so commit returns null regardless.
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // isWordEmpty
    // -------------------------------------------------------------------------

    @Test
    fun `isWordEmpty is true initially, false after a letter, true after commit`() {
        assertTrue(manager.isWordEmpty)
        manager.onLetterTyped('a', isSensitive = false)
        assertFalse(manager.isWordEmpty)
        manager.onWordCommit(isSensitive = false)
        assertTrue(manager.isWordEmpty)
    }

    // -------------------------------------------------------------------------
    // onWordDelete
    // -------------------------------------------------------------------------

    @Test
    fun `onWordDelete clears the buffer`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('i', isSensitive = false)
        manager.onWordDelete()
        assertTrue(manager.isWordEmpty)
    }

    @Test
    fun `onWordDelete on empty buffer does not crash`() {
        manager.onWordDelete()
        assertTrue(manager.isWordEmpty)
    }

    @Test
    fun `after onWordDelete commit with no new letters returns null`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onWordDelete()
        val result = manager.onWordCommit(isSensitive = false)
        assertNull(result)
    }

    @Test
    fun `onWordDelete suppression cleared by next letter then commit returns word`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onWordDelete()
        manager.onLetterTyped('o', isSensitive = false)
        manager.onLetterTyped('k', isSensitive = false)
        val result = manager.onWordCommit(isSensitive = false)
        assertEquals("ok", result)
    }

    // -------------------------------------------------------------------------
    // peekCurrentWord
    // -------------------------------------------------------------------------

    @Test
    fun `peekCurrentWord returns the current buffer without consuming it`() {
        manager.onLetterTyped('h', isSensitive = false)
        manager.onLetterTyped('e', isSensitive = false)
        assertEquals("he", manager.peekCurrentWord)
        // Buffer must still be intact: a subsequent commit returns the same content.
        manager.onLetterTyped('y', isSensitive = false)
        assertEquals("hey", manager.peekCurrentWord)
    }

    @Test
    fun `peekCurrentWord returns empty string when buffer is empty`() {
        assertEquals("", manager.peekCurrentWord)
    }

    // -------------------------------------------------------------------------
    // countCharsToDeleteForWord
    // -------------------------------------------------------------------------

    @Test
    fun `countCharsToDeleteForWord empty string returns 0`() {
        assertEquals(0, countCharsToDeleteForWord(""))
    }

    @Test
    fun `countCharsToDeleteForWord single word returns its length`() {
        assertEquals(5, countCharsToDeleteForWord("hello"))
    }

    @Test
    fun `countCharsToDeleteForWord word preceded by space returns word plus space length`() {
        assertEquals(6, countCharsToDeleteForWord("hello world"))
    }

    @Test
    fun `countCharsToDeleteForWord cursor after space deletes only the space`() {
        assertEquals(1, countCharsToDeleteForWord("hello "))
    }

    @Test
    fun `countCharsToDeleteForWord all whitespace deletes all of it`() {
        assertEquals(3, countCharsToDeleteForWord("   "))
    }

    @Test
    fun `countCharsToDeleteForWord single character returns 1`() {
        assertEquals(1, countCharsToDeleteForWord("a"))
    }

    // -------------------------------------------------------------------------
    // findWordInProgress
    // -------------------------------------------------------------------------

    @Test
    fun `findWordInProgress empty input returns null`() {
        assertNull(findWordInProgress(""))
    }

    @Test
    fun `findWordInProgress text ending in whitespace returns null`() {
        assertNull(findWordInProgress("hello "))
    }

    @Test
    fun `findWordInProgress single word returns the word`() {
        assertEquals("hello", findWordInProgress("hello"))
    }

    @Test
    fun `findWordInProgress multiple words returns the last one`() {
        assertEquals("world", findWordInProgress("hello world"))
    }

    @Test
    fun `findWordInProgress partial last word returns the partial`() {
        assertEquals("wor", findWordInProgress("hello wor"))
    }

    @Test
    fun `findWordInProgress preserves case so callers can detect leading capital`() {
        assertEquals("Hello", findWordInProgress("Hello"))
        assertEquals("HELLO", findWordInProgress("hi HELLO"))
    }

    @Test
    fun `findWordInProgress single character returns it`() {
        assertEquals("a", findWordInProgress("a"))
    }

    // -------------------------------------------------------------------------
    // SmartPunctuation (Session 43)
    // -------------------------------------------------------------------------

    @Test
    fun `attaching marks are recognised`() {
        for (c in listOf('.', ',', '?', '!', ':', ';')) {
            assertTrue("$c should attach", SmartPunctuation.isAttachingMark(c))
        }
    }

    @Test
    fun `non-attaching marks are not recognised`() {
        for (c in listOf('@', '#', '(', ')', '-', '/', '"', 'a', '1', ' ')) {
            assertFalse("$c should not attach", SmartPunctuation.isAttachingMark(c))
        }
    }

    @Test
    fun `shouldAttach true for an attaching mark after a pending space`() {
        assertTrue(SmartPunctuation.shouldAttach(pendingAutoSpace = true, charBeforeCursor = ' ', mark = "?"))
    }

    @Test
    fun `shouldAttach false when no auto-space is pending`() {
        assertFalse(SmartPunctuation.shouldAttach(pendingAutoSpace = false, charBeforeCursor = ' ', mark = "?"))
    }

    @Test
    fun `shouldAttach false when the char before the cursor is not a space`() {
        // Stale flag after a cursor move: the live char check must veto.
        assertFalse(SmartPunctuation.shouldAttach(pendingAutoSpace = true, charBeforeCursor = 'o', mark = "?"))
        assertFalse(SmartPunctuation.shouldAttach(pendingAutoSpace = true, charBeforeCursor = null, mark = "?"))
    }

    @Test
    fun `shouldAttach false for a non-attaching mark`() {
        assertFalse(SmartPunctuation.shouldAttach(pendingAutoSpace = true, charBeforeCursor = ' ', mark = "@"))
    }

    @Test
    fun `shouldAttach false for a multi-character key`() {
        assertFalse(SmartPunctuation.shouldAttach(pendingAutoSpace = true, charBeforeCursor = ' ', mark = ":)"))
    }
}
