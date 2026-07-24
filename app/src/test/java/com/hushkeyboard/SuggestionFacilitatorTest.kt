package com.hushkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for SuggestionFacilitator — the pure strip coordinator. No Android runtime.
 */
class SuggestionFacilitatorTest {

    // ---- Boundary (empty typed word): three neural next-words across the strip ----

    @Test
    fun `boundary fills three slots from neural in order`() {
        val s = SuggestionFacilitator.merge("", null, emptyList(), listOf("the", "a", "my"))
        assertEquals("the", s.left)
        assertEquals("a", s.center)
        assertEquals("my", s.right)
        assertNull("center holds a prediction, not a replacement", s.centerCommit)
    }

    @Test
    fun `boundary dedups neural case-insensitively and compacts`() {
        // "The" duplicates "the"; the third distinct word ("my") fills the right slot.
        val s = SuggestionFacilitator.merge("", null, emptyList(), listOf("the", "The", "a", "my"))
        assertEquals("the", s.left)
        assertEquals("a", s.center)
        assertEquals("my", s.right)
    }

    @Test
    fun `boundary with fewer than three neural words leaves trailing slots null`() {
        val s = SuggestionFacilitator.merge("", null, emptyList(), listOf("the"))
        assertEquals("the", s.left)
        assertNull(s.center)
        assertNull(s.right)
    }

    @Test
    fun `boundary with no neural words is all null`() {
        val s = SuggestionFacilitator.merge("", null, emptyList(), emptyList())
        assertNull(s.left)
        assertNull(s.center)
        assertNull(s.right)
        assertNull(s.centerCommit)
    }

    // ---- Typing: left = typed, center = correction|learned, right = neural look-ahead ----

    @Test
    fun `typing prefers the autocorrect correction in the center`() {
        val s = SuggestionFacilitator.merge("teh", "the", listOf("technology"), listOf("cat"))
        assertEquals("teh", s.left)
        assertEquals("the", s.center)
        assertEquals("the", s.centerCommit)
        assertEquals("cat", s.right)
    }

    @Test
    fun `typing falls back to the top learned completion when there is no correction`() {
        val s = SuggestionFacilitator.merge("znar", null, listOf("znargle", "znarp"), listOf("cat"))
        assertEquals("znar", s.left)
        assertEquals("znargle", s.center)
        assertEquals("znargle", s.centerCommit)
        assertEquals("cat", s.right)
    }

    @Test
    fun `typing leaves the center null when neither correction nor learned completion exists`() {
        val s = SuggestionFacilitator.merge("hello", null, emptyList(), listOf("world"))
        assertEquals("hello", s.left)
        assertNull(s.center)
        assertNull(s.centerCommit)
        assertEquals("world", s.right)
    }

    @Test
    fun `look-ahead skips a neural word that repeats the typed word`() {
        // The first neural next-word is the word being typed — fall through to the next one.
        val s = SuggestionFacilitator.merge("the", null, emptyList(), listOf("The", "cat"))
        assertEquals("cat", s.right)
    }

    @Test
    fun `look-ahead skips a neural word that repeats the center candidate`() {
        val s = SuggestionFacilitator.merge("teh", "the", emptyList(), listOf("the", "cat"))
        assertEquals("cat", s.right)
    }

    @Test
    fun `look-ahead is null when every neural word duplicates an earlier slot`() {
        val s = SuggestionFacilitator.merge("teh", "the", emptyList(), listOf("teh", "the"))
        assertNull(s.right)
    }

    @Test
    fun `a learned completion equal to the typed word is ignored`() {
        // Defensive: completionsFor never returns the prefix itself, but guard anyway.
        val s = SuggestionFacilitator.merge("the", null, listOf("the", "theory"), listOf("cat"))
        assertEquals("theory", s.center)
    }
}
