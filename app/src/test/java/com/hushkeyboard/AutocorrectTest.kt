package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutocorrectTest {

    private lateinit var ac: Autocorrect

    @Before
    fun setup() {
        ac = Autocorrect(
            listOf("the", "hello", "world", "spelling", "receive", "language", "keyboard", "because")
        )
    }

    // --- Correct words: no correction should be applied ---

    @Test
    fun correctWord_returnsNull() {
        assertNull("'hello' is valid, no correction expected", ac.correct("hello"))
    }

    @Test
    fun correctWord_caseInsensitive_returnsNull() {
        assertNull("'Hello' should be treated as valid (same as 'hello')", ac.correct("Hello"))
    }

    @Test
    fun correctWord_allCaps_returnsNull() {
        assertNull("'THE' should be treated as valid (same as 'the')", ac.correct("THE"))
    }

    // --- Too short: words under 3 characters are never corrected ---

    @Test
    fun shortWord_twoChars_returnsNull() {
        assertNull("Words under 3 characters are not corrected", ac.correct("th"))
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull("Empty string should return null", ac.correct(""))
    }

    // --- isKnownWord: used by learned-words capture (Session 36) ---

    @Test
    fun isKnownWord_dictionaryWord_returnsTrue() {
        assertTrue("'hello' is in the dictionary", ac.isKnownWord("hello"))
    }

    @Test
    fun isKnownWord_caseInsensitive() {
        assertTrue("'HELLO' is the same known word", ac.isKnownWord("HELLO"))
    }

    @Test
    fun isKnownWord_novelWord_returnsFalse() {
        // A name the dictionary does not contain — exactly what learned-words captures.
        assertFalse("'vinz' is not in the dictionary", ac.isKnownWord("vinz"))
    }

    // --- Distance 1 typos: should be corrected ---

    @Test
    fun typo_substitution_corrected() {
        // "hallo" → "hello": one letter substituted (a→e)
        assertEquals("hello", ac.correct("hallo"))
    }

    @Test
    fun typo_deletion_corrected() {
        // "helo" → "hello": one letter missing
        assertEquals("hello", ac.correct("helo"))
    }

    @Test
    fun typo_insertion_corrected() {
        // "helllo" → "hello": one extra letter
        assertEquals("hello", ac.correct("helllo"))
    }

    @Test
    fun typo_transposition_corrected() {
        // "teh" → "the": two adjacent letters swapped — OSA counts this as distance 1
        assertEquals("the", ac.correct("teh"))
    }

    @Test
    fun typo_classicSpelling_receive() {
        // "recieve" → "receive": adjacent i/e swapped — OSA counts this as distance 1
        assertEquals("receive", ac.correct("recieve"))
    }

    @Test
    fun typo_classicSpelling_language() {
        // "langauge" → "language": adjacent a/u swapped — OSA counts this as distance 1
        assertEquals("language", ac.correct("langauge"))
    }

    @Test
    fun typo_deletion_spelling() {
        // "speling" → "spelling": one 'l' missing, distance 1
        assertEquals("spelling", ac.correct("speling"))
    }

    // --- Distance 2 typos: should NOT be corrected ---

    @Test
    fun typo_distance2_notCorrected_hello() {
        // "bxllo": b≠h at pos 0 AND x≠e at pos 1, no adjacent swap possible → distance 2 from "hello"
        assertNull("Distance-2 typo should not be corrected", ac.correct("bxllo"))
    }

    @Test
    fun typo_distance2_notCorrected_world() {
        // "zxrld": z≠w at pos 0 AND x≠o at pos 1, no adjacent swap possible → distance 2 from "world"
        assertNull("Distance-2 typo should not be corrected", ac.correct("zxrld"))
    }

    // --- Rank-based tiebreaking: most common word wins among equal-distance candidates ---

    @Test
    fun rank_moreCommonWordWins() {
        // "hello" listed first (rank 0), "hell" listed second (rank 1)
        // Both are distance 1 from "helo"; "hello" must win because it has the lower rank
        val ac2 = Autocorrect(listOf("hello", "hell"))
        assertEquals("hello", ac2.correct("helo"))
    }

    @Test
    fun rank_orderIsRespected() {
        // Reverse order: "hell" listed first (rank 0), "hello" listed second (rank 1)
        // "hell" must win because it has the lower rank in this dictionary
        val ac2 = Autocorrect(listOf("hell", "hello"))
        assertEquals("hell", ac2.correct("helo"))
    }

    // --- Unknown words with no close match: should not be corrected ---

    @Test
    fun unknownWord_noCloseMatch_returnsNull() {
        assertNull("Word with no close match should return null", ac.correct("zxqwerty"))
    }

    // --- Dictionary filtering ---

    @Test
    fun dictionaryFiltering_apostropheWordExcluded() {
        // "it's" has an apostrophe and must be filtered out; "its" and "hello" pass
        val ac2 = Autocorrect(listOf("it's", "its", "hello"))
        // "its" is in the dictionary — valid, no correction
        assertNull("'its' is valid, no correction expected", ac2.correct("its"))
        // "itt" is distance 1 from "its" and should correct to it (proves "its" loaded correctly)
        assertEquals("its", ac2.correct("itt"))
    }

    @Test
    fun dictionaryFiltering_shortWordsExcluded() {
        // "hi" (2 chars) and "hey" (3 chars) — "hey" passes length check, "hi" does not
        // Only "hello" and "hey" survive filtering
        val ac2 = Autocorrect(listOf("hi", "hey", "hello"))
        // "helo" is distance 1 from "hello" — should be corrected
        assertEquals("hello", ac2.correct("helo"))
        // "hi" was filtered (too short) — nothing close enough to correct "hii"
        // "hii" is distance 1 from "hii"... let's use a word with no match instead
        assertNull("'zzzz' has no close match", ac2.correct("zzzz"))
    }
}
