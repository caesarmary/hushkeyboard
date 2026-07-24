package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * CandidateGeneratorTest — Phase 5 slice 1.
 *
 * Verifies the thin neighbor-list -> candidate-list transformation: the cap is respected, the
 * fast pick (head of the frequency-ranked neighbor list) is always included, and ordering is
 * stable (frequency rank). Pure-JVM JUnit4 — DEFINITION_OF_RIGHT Gate 8 Option 1.
 */
class CandidateGeneratorTest {

    // Neighbors as Autocorrect.neighbors() would supply them: frequency-rank order, fast pick
    // first.
    private val neighbors = listOf("help", "hello", "held", "hells", "helm")

    @Test
    fun capIsRespected() {
        val out = CandidateGenerator.forCorrection("helo", neighbors, cap = 3)
        assertEquals(3, out.size)
    }

    @Test
    fun fastPickAlwaysIncluded() {
        // The fast pick is the head of the neighbor list. It must survive any cap >= 1.
        val capped = CandidateGenerator.forCorrection("helo", neighbors, cap = 1)
        assertEquals(listOf("help"), capped)
        assertTrue("fast pick present even at cap=2", "help" in
            CandidateGenerator.forCorrection("helo", neighbors, cap = 2))
    }

    @Test
    fun orderIsStable_frequencyRank() {
        val out = CandidateGenerator.forCorrection("helo", neighbors, cap = 5)
        assertEquals(neighbors, out)
    }

    @Test
    fun capLargerThanInput_returnsAll() {
        val out = CandidateGenerator.forCorrection("helo", neighbors, cap = 100)
        assertEquals(neighbors, out)
    }

    @Test
    fun zeroCap_returnsEmpty() {
        assertTrue(CandidateGenerator.forCorrection("helo", neighbors, cap = 0).isEmpty())
    }

    @Test
    fun negativeCap_returnsEmpty() {
        assertTrue(CandidateGenerator.forCorrection("helo", neighbors, cap = -1).isEmpty())
    }

    @Test
    fun emptyNeighbors_returnsEmpty() {
        assertTrue(CandidateGenerator.forCorrection("helo", emptyList(), cap = 5).isEmpty())
    }

    @Test
    fun deduplicates_preservingFirstOccurrenceOrder() {
        // If a caller ever passes a list with a repeat, the output must not duplicate it, and
        // the first occurrence's position is kept.
        val dupy = listOf("help", "hello", "help", "held")
        val out = CandidateGenerator.forCorrection("helo", dupy, cap = 10)
        assertEquals(listOf("help", "hello", "held"), out)
    }

    @Test
    fun defaultCapConstant_usableAsCap() {
        // Sanity that the shared cap constant flows through unchanged.
        val out = CandidateGenerator.forCorrection(
            "helo", neighbors, cap = ContextRescorer.DEFAULT_MAX_CANDIDATES
        )
        assertEquals(neighbors, out)  // 5 neighbors < cap 8 -> all returned
    }
}
