package com.hushkeyboard

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * AutocorrectNeighborsTest — Phase 5 slice 1.
 *
 * Verifies the NEW `Autocorrect.neighbors(input, maxEdits)` candidate generator, which
 * generalizes `correct()`'s dist-1 search to dist-2 WITHOUT changing `correct()`. Two layers:
 *
 *   1. Small hand-built dictionaries (deterministic, no asset) — pin the contract: distance
 *      bound, frequency-rank order, dedup, length window.
 *   2. The REAL 50k asset (same loader pattern as AutocorrectJudgeTest) — prove dist-2 words
 *      appear that the dist-1-only `correct()` misses (the whole point of slice 1).
 *
 * Pure-JVM JUnit4, no Android, no device — DEFINITION_OF_RIGHT Gate 8 Option 1.
 */
class AutocorrectNeighborsTest {

    companion object {
        private lateinit var real: Autocorrect

        // Same loader the judge harness uses: Gradle runs unit tests with the app module as the
        // working dir, so the relative path resolves; the app/-prefixed path covers a repo-root run.
        @BeforeClass
        @JvmStatic
        fun loadRealDictionary() {
            val candidates = listOf(
                File("src/main/assets/wordlist_en.txt"),
                File("app/src/main/assets/wordlist_en.txt")
            )
            val asset = candidates.firstOrNull { it.exists() }
                ?: throw IllegalStateException(
                    "Could not locate wordlist_en.txt. Tried: " +
                        candidates.joinToString { it.absolutePath }
                )
            val lines = asset.readLines()
            require(lines.size > 10_000) {
                "wordlist looks truncated (${lines.size} lines) at ${asset.absolutePath}"
            }
            real = Autocorrect(lines)
        }
    }

    // --- Hand-built dictionary: contract tests ---

    @Test
    fun ed1_returnsDistanceOneNeighbors() {
        // "hello" listed first (rank 0), "hell" second. Both are within OSA 1 of "helo".
        val ac = Autocorrect(listOf("hello", "hell", "world"))
        val n = ac.neighbors("helo", 1)
        assertTrue("'hello' is dist-1 from 'helo'", "hello" in n)
        assertTrue("'hell' is dist-1 from 'helo'", "hell" in n)
        assertFalse("'world' is far from 'helo'", "world" in n)
    }

    @Test
    fun ed1_frequencyRankOrder() {
        // Rank order: hello(0) before hell(1). neighbors() must return most-common first.
        val ac = Autocorrect(listOf("hello", "hell"))
        assertEquals(listOf("hello", "hell"), ac.neighbors("helo", 1))
    }

    @Test
    fun ed1_frequencyRankOrder_reversed() {
        // Reverse the list -> hell(0) before hello(1). Order must follow rank, not input order.
        val ac = Autocorrect(listOf("hell", "hello"))
        assertEquals(listOf("hell", "hello"), ac.neighbors("helo", 1))
    }

    @Test
    fun ed2_findsDistanceTwoThatEd1Misses() {
        // "abcde" -> "abxye" is OSA distance 2 (two substitutions). ed-1 must NOT find it; ed-2
        // must. Proves maxEdits actually widens the radius.
        val ac = Autocorrect(listOf("abxye"))
        assertFalse("dist-2 word absent at maxEdits=1", "abxye" in ac.neighbors("abcde", 1))
        assertTrue("dist-2 word present at maxEdits=2", "abxye" in ac.neighbors("abcde", 2))
    }

    @Test
    fun ed2_lengthWindowWidened() {
        // "tomorrow" (len 8) is dist-2 from "tommorow" (len 8) but the search must also scan
        // len-2..len+2 buckets in general. Here a shorter dict word two deletions away:
        // "test" (len 4) is dist-2 from "tests" + an extra... use a clean len-2 case:
        // "abcd" (len 4) vs typed "abcdef" (len 6): two deletions -> OSA 2, two length steps.
        val ac = Autocorrect(listOf("abcd"))
        assertFalse("len-2 neighbor absent at maxEdits=1", "abcd" in ac.neighbors("abcdef", 1))
        assertTrue("len-2 neighbor present at maxEdits=2", "abcd" in ac.neighbors("abcdef", 2))
    }

    @Test
    fun deduplicated() {
        // A dictionary cannot hold the same word twice (addWords dedups on insert), but the
        // neighbors() result must in any case contain no duplicate strings.
        val ac = Autocorrect(listOf("hello", "hell", "help"))
        val n = ac.neighbors("helo", 2)
        assertEquals("no duplicate neighbor strings", n.size, n.toSet().size)
    }

    @Test
    fun ed2_isSupersetOfEd1() {
        // Everything ed-1 finds, ed-2 must also find (distance bound is inclusive and wider).
        val ac = Autocorrect(listOf("hello", "hell", "help", "held", "hells"))
        val ed1 = ac.neighbors("helo", 1).toSet()
        val ed2 = ac.neighbors("helo", 2).toSet()
        assertTrue("ed-2 must contain every ed-1 neighbor", ed2.containsAll(ed1))
    }

    // --- Real asset: prove the slice-1 wins are reachable ---

    @Test
    fun real_helo_hasBothHelpAndHello() {
        // The headline over-correction case A4: today correct("helo") commits "help" (more
        // frequent) but the user usually means "hello". Both must be in the dist-1 neighbor set
        // so the rescorer has both to choose between.
        val n = real.neighbors("helo", 1)
        assertTrue("'hello' must be a neighbor of 'helo'", "hello" in n)
        assertTrue("'help' must be a neighbor of 'helo'", "help" in n)
        // And 'help' precedes 'hello' (it is the more frequent / lower-rank word) — confirms the
        // frequency ordering matches what makes 'help' today's fast pick.
        assertTrue(
            "'help' (more common) must rank before 'hello'",
            n.indexOf("help") < n.indexOf("hello")
        )
    }

    @Test
    fun real_definatly_ed2_findsDefinitely_thatEd1Misses() {
        // A5/B5: "definatly". "defiantly" sits at dist-1 (today's wrong pick); the intended
        // "definitely" is dist-2. ed-1 must miss "definitely"; ed-2 must surface it.
        val ed1 = real.neighbors("definatly", 1)
        val ed2 = real.neighbors("definatly", 2)
        assertTrue("'defiantly' is the dist-1 distractor", "defiantly" in ed1)
        assertFalse("'definitely' is NOT reachable at dist-1", "definitely" in ed1)
        assertTrue("'definitely' IS reachable at dist-2", "definitely" in ed2)
    }

    @Test
    fun real_tommorow_ed2_findsTomorrow_thatEd1Misses() {
        // A6/B6: "tommorow" -> "tomorrow" is dist-2; correct() returns null today. ed-2 finds it.
        val ed1 = real.neighbors("tommorow", 1)
        val ed2 = real.neighbors("tommorow", 2)
        assertFalse("'tomorrow' is NOT reachable at dist-1", "tomorrow" in ed1)
        assertTrue("'tomorrow' IS reachable at dist-2", "tomorrow" in ed2)
    }

    @Test
    fun real_neighborsAgreeWithCorrect_forItsFastPick() {
        // Cross-check: whatever correct() picks for a typo, that word must appear among the
        // dist-1 neighbors (neighbors generalizes the same search). Guards against the two
        // diverging.
        val pick = real.correct("helo")
        assertNotNull("sanity: real dict loaded, correct('helo') non-null", pick)
        assertTrue(
            "correct()'s pick ($pick) must be among neighbors('helo', 1)",
            pick in real.neighbors("helo", 1)
        )
    }
}
