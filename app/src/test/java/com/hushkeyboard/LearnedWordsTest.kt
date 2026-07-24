package com.hushkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LearnedWords — the pure in-memory dictionary model and its
 * learnability rules. No Android runtime needed.
 */
class LearnedWordsTest {

    // ---- learn / count ----

    @Test
    fun `a new word is learned with count one`() {
        val w = LearnedWords()
        assertTrue(w.learn("hello"))
        assertEquals(1, w.count("hello"))
        assertEquals(1, w.size)
    }

    @Test
    fun `learning the same word again increments its count`() {
        val w = LearnedWords()
        w.learn("hello")
        w.learn("hello")
        w.learn("hello")
        assertEquals(3, w.count("hello"))
        assertEquals(1, w.size)
    }

    @Test
    fun `learning is case-insensitive`() {
        val w = LearnedWords()
        w.learn("Hello")
        w.learn("HELLO")
        assertEquals(2, w.count("hello"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val w = LearnedWords()
        w.learn("  hello  ")
        assertEquals(1, w.count("hello"))
    }

    @Test
    fun `count of an unlearned word is zero`() {
        assertEquals(0, LearnedWords().count("absent"))
    }

    // ---- learnability rules ----

    @Test
    fun `words with apostrophes are learnable`() {
        assertTrue(LearnedWords.isLearnable("don't"))
        assertTrue(LearnedWords().learn("don't"))
    }

    @Test
    fun `single-character words are not learnable`() {
        assertFalse(LearnedWords.isLearnable("a"))
        assertFalse(LearnedWords().learn("a"))
    }

    @Test
    fun `words containing digits are not learnable`() {
        assertFalse(LearnedWords.isLearnable("abc123"))
        assertFalse(LearnedWords().learn("abc123"))
    }

    @Test
    fun `words containing symbols are not learnable`() {
        assertFalse(LearnedWords.isLearnable("hi!"))
        assertFalse(LearnedWords.isLearnable("a@b"))
    }

    @Test
    fun `a lone apostrophe is not learnable`() {
        assertFalse(LearnedWords.isLearnable("''"))
    }

    @Test
    fun `words containing whitespace are not learnable`() {
        // Guarantees no tab/newline can reach the on-disk line format.
        assertFalse(LearnedWords.isLearnable("two words"))
        assertFalse(LearnedWords.isLearnable("tab\there"))
    }

    @Test
    fun `over-long words are not learnable`() {
        val tooLong = "a".repeat(LearnedWords.MAX_WORD_LENGTH + 1)
        assertFalse(LearnedWords.isLearnable(tooLong))
    }

    // ---- capacity / eviction ----

    @Test
    fun `dictionary is bounded and evicts the least-used word`() {
        val w = LearnedWords(maxWords = 3)
        // alpha used 3x, beta 2x, gamma 1x -> gamma is least used.
        repeat(3) { w.learn("alpha") }
        repeat(2) { w.learn("beta") }
        w.learn("gamma")
        assertEquals(3, w.size)
        // Adding a 4th distinct word evicts gamma (lowest count), not alpha/beta.
        w.learn("delta")
        assertEquals(3, w.size)
        assertEquals(0, w.count("gamma"))
        assertEquals(3, w.count("alpha"))
        assertEquals(1, w.count("delta"))
    }

    @Test
    fun `incrementing an existing word never triggers eviction`() {
        val w = LearnedWords(maxWords = 2)
        w.learn("alpha")
        w.learn("beta")
        // Re-learning beta must not drop alpha — the dictionary is already full but
        // this is not a new key.
        w.learn("beta")
        assertEquals(1, w.count("alpha"))
        assertEquals(2, w.count("beta"))
    }

    // ---- replaceAll / clear (load + user clear) ----

    @Test
    fun `replaceAll loads entries and drops the rest beyond capacity`() {
        val w = LearnedWords(maxWords = 2)
        w.replaceAll(mapOf("low" to 1, "high" to 10, "mid" to 5))
        // Keeps the two highest counts.
        assertEquals(2, w.size)
        assertEquals(10, w.count("high"))
        assertEquals(5, w.count("mid"))
        assertEquals(0, w.count("low"))
    }

    @Test
    fun `replaceAll skips invalid entries`() {
        val w = LearnedWords()
        w.replaceAll(mapOf("ok" to 2, "bad word" to 9, "x" to 4))
        assertEquals(1, w.size)
        assertEquals(2, w.count("ok"))
    }

    @Test
    fun `snapshot reflects contents and clear empties the dictionary`() {
        val w = LearnedWords()
        w.learn("hello")
        w.learn("world")
        assertEquals(setOf("hello", "world"), w.snapshot().keys)
        w.clear()
        assertEquals(0, w.size)
        assertTrue(w.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is a copy and does not mutate the dictionary`() {
        val w = LearnedWords()
        w.learn("hello")
        val snap = w.snapshot() as MutableMap
        snap.clear()
        assertEquals(1, w.count("hello")) // unaffected
    }

    // ---- completionsFor (Session 36 consumption: prefix completion) ----

    @Test
    fun `completionsFor returns words starting with the prefix`() {
        val counts = mapOf("vinz" to 3, "vincent" to 1, "hello" to 5)
        val out = LearnedWords.completionsFor(counts, "vin", 3)
        assertEquals(listOf("vinz", "vincent"), out) // "hello" excluded; vinz (3) before vincent (1)
    }

    @Test
    fun `completionsFor ranks by count, highest first`() {
        val counts = mapOf("vincent" to 1, "vinz" to 9)
        assertEquals(listOf("vinz", "vincent"), LearnedWords.completionsFor(counts, "vin", 3))
    }

    @Test
    fun `completionsFor breaks count ties by insertion order`() {
        // A LinkedHashMap snapshot preserves insertion order; equal counts keep it (stable sort).
        val counts = linkedMapOf("vina" to 2, "vinb" to 2, "vinc" to 2)
        assertEquals(listOf("vina", "vinb", "vinc"), LearnedWords.completionsFor(counts, "vin", 3))
    }

    @Test
    fun `completionsFor honours the limit`() {
        val counts = mapOf("vina" to 4, "vinb" to 3, "vinc" to 2, "vind" to 1)
        assertEquals(listOf("vina", "vinb"), LearnedWords.completionsFor(counts, "vin", 2))
    }

    @Test
    fun `completionsFor excludes a word equal to the prefix`() {
        // The user already typed the whole word; do not offer it back to them.
        val counts = mapOf("vin" to 5, "vinz" to 1)
        assertEquals(listOf("vinz"), LearnedWords.completionsFor(counts, "vin", 3))
    }

    @Test
    fun `completionsFor is case-insensitive on the prefix`() {
        val counts = mapOf("vinz" to 1)
        assertEquals(listOf("vinz"), LearnedWords.completionsFor(counts, "VIN", 3))
    }

    @Test
    fun `completionsFor returns nothing for a too-short prefix`() {
        val counts = mapOf("vinz" to 1)
        assertTrue(LearnedWords.completionsFor(counts, "v", 3).isEmpty())
    }

    @Test
    fun `completionsFor returns nothing when no word matches`() {
        val counts = mapOf("hello" to 1, "world" to 1)
        assertTrue(LearnedWords.completionsFor(counts, "vin", 3).isEmpty())
    }

    @Test
    fun `completionsFor returns nothing for a non-positive limit`() {
        val counts = mapOf("vinz" to 1)
        assertTrue(LearnedWords.completionsFor(counts, "vin", 0).isEmpty())
    }
}
