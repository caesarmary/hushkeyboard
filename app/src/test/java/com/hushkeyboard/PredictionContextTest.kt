package com.hushkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PredictionContext] — the pure layer of the Phase 4 next-word
 * predictor, including the SECURITY-CRITICAL eligibility gate (SECURITY.md
 * rule 4: prediction must never run in a password field).
 *
 * Raw inputType integers are used (not the Android constants) so these run on a
 * plain JVM with no Android runtime — mirroring SensitiveFieldCheckerTest.
 */
class PredictionContextTest {

    // Raw android.text.InputType bits (stable; Android cannot change them).
    private val textClass = 0x00000001
    private val numberClass = 0x00000002
    private val varPassword = 0x00000080
    private val varVisiblePassword = 0x00000090
    private val varWebPassword = 0x000000E0
    private val numberPassword = 0x00000010
    private val flagNoSuggestions = 0x00080000

    // -------------------------------------------------------------------------
    // isEligible — the security gate
    // -------------------------------------------------------------------------

    @Test
    fun `eligible in a normal text field`() {
        assertTrue(PredictionContext.isEligible(textClass, suggestionsEnabled = true))
    }

    @Test
    fun `not eligible in a text password field`() {
        assertFalse(PredictionContext.isEligible(textClass or varPassword, true))
    }

    @Test
    fun `not eligible in a visible password field`() {
        assertFalse(PredictionContext.isEligible(textClass or varVisiblePassword, true))
    }

    @Test
    fun `not eligible in a web password field`() {
        assertFalse(PredictionContext.isEligible(textClass or varWebPassword, true))
    }

    @Test
    fun `not eligible in a numeric password field`() {
        assertFalse(PredictionContext.isEligible(numberClass or numberPassword, true))
    }

    @Test
    fun `eligible in a no-suggestions field (search) when suggestions on`() {
        // Reading a search query carries no privacy risk — same stance as long-press delete.
        assertTrue(PredictionContext.isEligible(textClass or flagNoSuggestions, true))
    }

    @Test
    fun `not eligible when suggestions disabled, even in a normal field`() {
        assertFalse(PredictionContext.isEligible(textClass, suggestionsEnabled = false))
    }

    @Test
    fun `disabled suggestions never overrides into a password field`() {
        assertFalse(PredictionContext.isEligible(textClass or varPassword, suggestionsEnabled = false))
    }

    // -------------------------------------------------------------------------
    // buildContext — trailing-whitespace stripping for next-word prediction
    // -------------------------------------------------------------------------

    @Test
    fun `buildContext strips a single trailing space`() {
        assertEquals("Thank you very", PredictionContext.buildContext("Thank you very "))
    }

    @Test
    fun `buildContext strips multiple trailing spaces and newline`() {
        assertEquals("Hello", PredictionContext.buildContext("Hello  \n"))
    }

    @Test
    fun `buildContext keeps internal spaces`() {
        assertEquals("the cat sat", PredictionContext.buildContext("the cat sat "))
    }

    @Test
    fun `buildContext returns null for null`() {
        assertNull(PredictionContext.buildContext(null))
    }

    @Test
    fun `buildContext returns null for empty`() {
        assertNull(PredictionContext.buildContext(""))
    }

    @Test
    fun `buildContext returns null for whitespace only`() {
        assertNull(PredictionContext.buildContext("   "))
    }

    @Test
    fun `buildContext leaves a context ending in punctuation untouched`() {
        assertEquals("Hello.", PredictionContext.buildContext("Hello."))
    }

    // -------------------------------------------------------------------------
    // pickNextWord — choose a leading-space, word-bearing candidate
    // -------------------------------------------------------------------------

    @Test
    fun `pickNextWord takes the first leading-space word`() {
        assertEquals("much", PredictionContext.pickNextWord(listOf(" much", " well")))
    }

    @Test
    fun `pickNextWord skips candidates with no leading space`() {
        // A non-space token is a mid-word continuation, not a next word — skip it.
        assertEquals("the", PredictionContext.pickNextWord(listOf("ing", " the", " cat")))
    }

    @Test
    fun `pickNextWord skips pure-punctuation candidates`() {
        assertEquals("yes", PredictionContext.pickNextWord(listOf(" .", " !", " yes")))
    }

    @Test
    fun `pickNextWord accepts a leading-space number word`() {
        assertEquals("100", PredictionContext.pickNextWord(listOf(" 100")))
    }

    @Test
    fun `pickNextWord returns null when nothing qualifies`() {
        assertNull(PredictionContext.pickNextWord(listOf("ing", " .", "", " ")))
    }

    @Test
    fun `pickNextWord returns null for empty candidate list`() {
        assertNull(PredictionContext.pickNextWord(emptyList()))
    }

    @Test
    fun `pickNextWord trims surrounding space to a clean word`() {
        assertEquals("world", PredictionContext.pickNextWord(listOf(" world")))
    }

    // -------------------------------------------------------------------------
    // pickNextWordIndex — same predicate as pickNextWord, but returns the index
    // (so the engine can recover the chosen token id for greedy continuation)
    // -------------------------------------------------------------------------

    @Test
    fun `pickNextWordIndex returns the first qualifying index`() {
        assertEquals(0, PredictionContext.pickNextWordIndex(listOf(" much", " well")))
    }

    @Test
    fun `pickNextWordIndex skips non-qualifying leading entries`() {
        // "ing" (no leading space) and " ." (punctuation) are skipped; " the" wins.
        assertEquals(2, PredictionContext.pickNextWordIndex(listOf("ing", " .", " the")))
    }

    @Test
    fun `pickNextWordIndex returns -1 when nothing qualifies`() {
        assertEquals(-1, PredictionContext.pickNextWordIndex(listOf("ing", " .", "", " ")))
    }

    @Test
    fun `pickNextWordIndex agrees with pickNextWord on the chosen candidate`() {
        val candidates = listOf("ed", " .", " brilliant", " ok")
        val idx = PredictionContext.pickNextWordIndex(candidates)
        assertEquals(PredictionContext.pickNextWord(candidates), candidates[idx].trim())
    }

    // -------------------------------------------------------------------------
    // nextWordIndices — all qualifying indices, in rank order (Session 31, used
    // by predictTopWords to fill the whole strip)
    // -------------------------------------------------------------------------

    @Test
    fun `nextWordIndices returns all qualifying indices in order`() {
        assertEquals(listOf(0, 1), PredictionContext.nextWordIndices(listOf(" much", " well")))
    }

    @Test
    fun `nextWordIndices skips continuations and punctuation`() {
        // "ing" (no leading space) and " ." (punctuation) are skipped; words at 1 and 3 qualify.
        assertEquals(listOf(1, 3), PredictionContext.nextWordIndices(listOf("ing", " the", " .", " cat")))
    }

    @Test
    fun `nextWordIndices returns empty when nothing qualifies`() {
        assertTrue(PredictionContext.nextWordIndices(listOf("ing", " .", "", " ")).isEmpty())
    }

    @Test
    fun `nextWordIndices returns empty for empty candidate list`() {
        assertTrue(PredictionContext.nextWordIndices(emptyList()).isEmpty())
    }

    @Test
    fun `nextWordIndices first element agrees with pickNextWordIndex`() {
        val candidates = listOf("ed", " .", " brilliant", " ok")
        assertEquals(PredictionContext.pickNextWordIndex(candidates),
            PredictionContext.nextWordIndices(candidates).first())
    }

    // -------------------------------------------------------------------------
    // isWordContinuation — the greedy multi-token stop/continue rule
    // -------------------------------------------------------------------------

    @Test
    fun `isWordContinuation true for a letter subword with no leading space`() {
        assertTrue(PredictionContext.isWordContinuation("ing"))
        assertTrue(PredictionContext.isWordContinuation("able"))
    }

    @Test
    fun `isWordContinuation true for an apostrophe contraction piece`() {
        // "n't" / "'s" continue the current word (they contain letters).
        assertTrue(PredictionContext.isWordContinuation("n't"))
    }

    @Test
    fun `isWordContinuation false for a leading-space new word`() {
        assertFalse(PredictionContext.isWordContinuation(" the"))
    }

    @Test
    fun `isWordContinuation false for pure punctuation`() {
        assertFalse(PredictionContext.isWordContinuation("."))
        assertFalse(PredictionContext.isWordContinuation(","))
    }

    @Test
    fun `isWordContinuation false for empty string`() {
        assertFalse(PredictionContext.isWordContinuation(""))
    }

    @Test
    fun `isWordContinuation true for a digit continuation`() {
        // e.g. completing "covid" + "19"
        assertTrue(PredictionContext.isWordContinuation("19"))
    }

    // -------------------------------------------------------------------------
    // capitalizeForSentenceStart — capitalize only at the start of a sentence
    // -------------------------------------------------------------------------

    @Test
    fun `capitalize after a period`() {
        assertEquals("It", PredictionContext.capitalizeForSentenceStart("it", "Hello."))
    }

    @Test
    fun `capitalize after a question mark`() {
        assertEquals("Yes", PredictionContext.capitalizeForSentenceStart("yes", "Really?"))
    }

    @Test
    fun `capitalize after an exclamation mark`() {
        assertEquals("Wow", PredictionContext.capitalizeForSentenceStart("wow", "Stop!"))
    }

    @Test
    fun `capitalize when context is empty`() {
        assertEquals("Hello", PredictionContext.capitalizeForSentenceStart("hello", ""))
    }

    @Test
    fun `capitalize when context is whitespace only`() {
        assertEquals("Hello", PredictionContext.capitalizeForSentenceStart("hello", "   "))
    }

    @Test
    fun `does not capitalize mid-sentence`() {
        assertEquals("much", PredictionContext.capitalizeForSentenceStart("much", "Thank you very"))
    }

    @Test
    fun `tolerates trailing space before the sentence-ending period`() {
        // buildContext strips trailing space, but be robust if a space slips through.
        assertEquals("It", PredictionContext.capitalizeForSentenceStart("it", "Hello.  "))
    }

    @Test
    fun `already-capitalized word is unchanged at a sentence start`() {
        assertEquals("Paris", PredictionContext.capitalizeForSentenceStart("Paris", "I love."))
    }

    @Test
    fun `empty word stays empty`() {
        assertEquals("", PredictionContext.capitalizeForSentenceStart("", "Hello."))
    }

    @Test
    fun `honest limit - trailing quote after period is not unwrapped`() {
        // Documented v1 behavior: a quote/bracket after the period means the next
        // word is NOT seen as a sentence start, so it is left lowercase.
        assertEquals("it", PredictionContext.capitalizeForSentenceStart("it", "\"Stop.\""))
    }
}
