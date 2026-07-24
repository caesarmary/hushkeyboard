package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * ContextRescorerTest — Phase 5 slice 1.
 *
 * Verifies the decision core with INJECTED scores (no model, no device). Proves the headline
 * behaviors: the over-correction fix fires when a candidate clearly wins, and the rescorer stays
 * conservative (keeps the fast pick) for ties, near-ties, and degenerate inputs.
 *
 * Pure-JVM JUnit4 — DEFINITION_OF_RIGHT Gate 8 Option 1.
 */
class ContextRescorerTest {

    // A score table backed by a map; missing words score very low so they never win by accident.
    private fun scorer(table: Map<String, Double>): (String) -> Double =
        { table[it] ?: Double.NEGATIVE_INFINITY }

    private val margin = ContextRescorer.DEFAULT_OVERRIDE_MARGIN_NATS

    // (a) Over-correction fix: "hello" beats the fast pick "help" by MORE than the margin ->
    // decide returns "hello". This is the A4/B4 behavior the whole slice exists for.
    @Test
    fun override_whenCandidateBeatsFastPickByMoreThanMargin() {
        val score = scorer(
            mapOf(
                "help" to 0.0,
                "hello" to margin + 0.5,   // clears the bar with room to spare
                "held" to -2.0
            )
        )
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello", "held"),
            score = score
        )
        assertEquals("hello", winner)
    }

    // (b) Conservative: scores tied -> keep the fast pick. The model offers no improvement.
    @Test
    fun keepsFastPick_whenScoresTied() {
        val score = scorer(mapOf("help" to 1.0, "hello" to 1.0, "held" to 1.0))
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello", "held"),
            score = score
        )
        assertEquals("help", winner)
    }

    // (b') Conservative: challenger leads but by LESS than the margin -> keep the fast pick.
    @Test
    fun keepsFastPick_whenChallengerLeadsButBelowMargin() {
        val score = scorer(
            mapOf(
                "help" to 0.0,
                "hello" to margin - 0.001   // strictly below the override bar
            )
        )
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello"),
            score = score
        )
        assertEquals("help", winner)
    }

    // (c) Boundary: challenger wins by EXACTLY the margin -> overrides (>= is inclusive).
    @Test
    fun overridesAtExactMarginBoundary() {
        val score = scorer(mapOf("help" to 0.0, "hello" to margin))
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello"),
            score = score
        )
        assertEquals("hello", winner)
    }

    // (c') Just below the boundary -> keep the fast pick. Pins the boundary from the other side.
    @Test
    fun keepsFastPickJustBelowMarginBoundary() {
        val nextDown = Math.nextAfter(margin, Double.NEGATIVE_INFINITY)
        val score = scorer(mapOf("help" to 0.0, "hello" to nextDown))
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello"),
            score = score
        )
        assertEquals("help", winner)
    }

    // (d) Degenerate: empty candidate list -> fast pick (graceful degrade).
    @Test
    fun emptyCandidates_returnsFastPick() {
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = emptyList(),
            score = scorer(mapOf("help" to 0.0))
        )
        assertEquals("help", winner)
    }

    // (d') Degenerate: only the fast pick is a candidate -> fast pick (no challenger exists).
    @Test
    fun singleCandidateIsFastPick_returnsFastPick() {
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help"),
            score = scorer(mapOf("help" to 5.0))
        )
        assertEquals("help", winner)
    }

    // The fast pick need not be present in the candidate list; a strong challenger still wins
    // if it clears the margin against the (separately scored) fast pick.
    @Test
    fun fastPickAbsentFromCandidates_strongChallengerStillOverrides() {
        val score = scorer(mapOf("help" to 0.0, "hello" to margin + 1.0))
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("hello"),
            score = score
        )
        assertEquals("hello", winner)
    }

    // A custom (smaller) margin makes the rescorer more aggressive; confirm the parameter is
    // honored rather than the constant being hard-wired.
    @Test
    fun customMargin_isHonored() {
        val score = scorer(mapOf("help" to 0.0, "hello" to 0.5))
        // Default margin (1.5) would keep "help"; an explicit 0.4 margin lets "hello" win.
        assertEquals(
            "help",
            ContextRescorer.decide("help", listOf("help", "hello"), score)
        )
        assertEquals(
            "hello",
            ContextRescorer.decide("help", listOf("help", "hello"), score, overrideMarginNats = 0.4)
        )
    }

    // Among several challengers, the single best-scoring one is chosen (and only if it clears
    // the margin). Verifies the argmax, not just "some override happened."
    @Test
    fun picksHighestScoringChallenger() {
        val score = scorer(
            mapOf(
                "help" to 0.0,
                "hello" to margin + 0.2,
                "hells" to margin + 2.0,   // the actual best
                "held" to margin + 0.1
            )
        )
        val winner = ContextRescorer.decide(
            fastPick = "help",
            candidates = listOf("help", "hello", "hells", "held"),
            score = score
        )
        assertEquals("hells", winner)
    }

    // =======================================================================
    // decideCorrection — the slice-1b batch orchestration. Same injected-score
    // posture (no model), but exercises the candidate build + incumbent
    // selection + batch->decide bridge. The model's REAL ranking is verified
    // on-device; here scoreAll is a stub standing in for it.
    // =======================================================================

    // A batch scorer from a table, positionally aligned to the candidate list it is handed.
    private fun batchScorer(table: Map<String, Double>): (List<String>) -> DoubleArray =
        { cands -> DoubleArray(cands.size) { i -> table[cands[i]] ?: Double.NEGATIVE_INFINITY } }

    // B4 shape: "helo" fast-picks "help"; the stub model prefers "hello" by > margin -> override.
    @Test
    fun decideCorrection_overridesFastPickWhenModelPrefersOther() {
        val winner = ContextRescorer.decideCorrection(
            typed = "helo",
            neighbors = listOf("help", "hello", "held"),   // frequency-rank order
            fastPick = "help",
            scoreAll = batchScorer(mapOf("help" to 0.0, "hello" to margin + 0.5, "held" to -3.0))
        )
        assertEquals("hello", winner)
    }

    // B6 shape: "tommorow" has no ed-1 fix (fastPick == null), so the TYPED WORD is the incumbent.
    // It is a genuine typo and scores poorly; the correct neighbor beats it by > margin -> override.
    @Test
    fun decideCorrection_nullFastPick_typedWordIsIncumbent_realTypoOverrides() {
        val winner = ContextRescorer.decideCorrection(
            typed = "tommorow",
            neighbors = listOf("tomorow", "tomorrow"),
            fastPick = null,
            scoreAll = batchScorer(
                mapOf("tommorow" to 0.0, "tomorow" to 0.2, "tomorrow" to margin + 1.0)
            )
        )
        assertEquals("tomorrow", winner)
    }

    // The over-correction guard (Session 75): with fastPick == null, a PLAUSIBLE novel word (a name/
    // slang) is NOT clobbered. Even when the model leans toward a dictionary neighbor, if that lead is
    // BELOW the margin against the typed word's own score, the typed word is kept. Before this guard
    // the ed-2 path replaced every long non-word that merely had a dictionary neighbor.
    @Test
    fun decideCorrection_nullFastPick_plausibleNovelWord_keptWhenNoNeighborClearsMargin() {
        val winner = ContextRescorer.decideCorrection(
            typed = "reyes",                                // a surname; has neighbors (eyes, byes…)
            neighbors = listOf("eyes", "byes", "ryes"),
            fastPick = null,
            scoreAll = batchScorer(
                mapOf("reyes" to 0.0, "eyes" to margin - 0.5, "byes" to -1.0, "ryes" to -2.0)
            )
        )
        assertEquals("reyes", winner)
    }

    // Null fastPick + a flat (uninformative) model -> keep the typed word, never silently swap it for
    // a neighbor. With the typed word now the incumbent, a flat model can't clear the margin.
    @Test
    fun decideCorrection_nullFastPick_flatModel_keepsTypedWord() {
        val winner = ContextRescorer.decideCorrection(
            typed = "tommorow",
            neighbors = listOf("tomorow", "tomorrow"),
            fastPick = null,
            scoreAll = batchScorer(mapOf("tommorow" to 1.0, "tomorow" to 1.0, "tomorrow" to 1.0))
        )
        assertEquals("tommorow", winner)
    }

    // No candidates AND no fast pick -> nothing to commit. Caller leaves the typo untouched.
    @Test
    fun decideCorrection_noCandidatesNoFastPick_returnsNull() {
        val winner = ContextRescorer.decideCorrection(
            typed = "zzzz",
            neighbors = emptyList(),
            fastPick = null,
            scoreAll = batchScorer(emptyMap())
        )
        assertNull(winner)
    }

    // The cap can drop a low-frequency fastPick out of the generated set; it must still be scored
    // (as the incumbent) rather than silently lost. Here a tiny cap excludes "defiantly" from the
    // generated head, yet a strong model score for "definitely" still produces the override.
    @Test
    fun decideCorrection_incumbentCappedOut_isStillScoredAndComparable() {
        val winner = ContextRescorer.decideCorrection(
            typed = "definatly",
            neighbors = listOf("definitely", "defiantly", "deflate"),
            fastPick = "defiantly",                    // not at the head; cap=1 drops it from generated
            scoreAll = batchScorer(mapOf("definitely" to margin + 2.0, "defiantly" to 0.0)),
            maxCandidates = 1
        )
        assertEquals("definitely", winner)
    }

    // scoreAll must return exactly one score per candidate; a mismatch is a wiring bug and fails
    // loudly rather than scoring the wrong word.
    @Test(expected = IllegalArgumentException::class)
    fun decideCorrection_scoreCountMismatch_throws() {
        ContextRescorer.decideCorrection(
            typed = "helo",
            neighbors = listOf("help", "hello"),
            fastPick = "help",
            scoreAll = { DoubleArray(1) }              // wrong length on purpose
        )
    }

    // =======================================================================
    // decideRealWordOffer — slice 2. The typed word is correctly spelled; an
    // alternative is OFFERED only when the model prefers it by >= the (larger)
    // real-word margin. Injected scores; the model's real ranking is verified
    // on-device (Session 72 probe).
    // =======================================================================

    private val realMargin = ContextRescorer.DEFAULT_REALWORD_OVERRIDE_MARGIN_NATS

    // The headline case: "there" was typed but context strongly prefers "their" -> offer "their".
    @Test
    fun decideRealWordOffer_offersAlternativeWhenItClearsTheMargin() {
        val offer = ContextRescorer.decideRealWordOffer(
            typed = "there",
            confusionSet = listOf("there", "their", "they're"),
            scoreAll = batchScorer(mapOf("there" to 0.0, "their" to realMargin + 1.0, "they're" to -2.0))
        )
        assertEquals("their", offer)
    }

    // Conservative: the model leans toward an alternative but below the real-word bar (the kind of
    // genuinely-ambiguous context the Session 72 probe measured at 0.21/0.56 nats) -> NO offer.
    @Test
    fun decideRealWordOffer_staysSilentBelowMargin() {
        val offer = ContextRescorer.decideRealWordOffer(
            typed = "there",
            confusionSet = listOf("there", "their", "they're"),
            scoreAll = batchScorer(mapOf("there" to 0.0, "their" to 0.56, "they're" to 0.21))
        )
        assertNull(offer)
    }

    // The typed word already wins -> no offer (nothing to correct).
    @Test
    fun decideRealWordOffer_noOfferWhenTypedWordWins() {
        val offer = ContextRescorer.decideRealWordOffer(
            typed = "their",
            confusionSet = listOf("there", "their", "they're"),
            scoreAll = batchScorer(mapOf("there" to -1.0, "their" to 2.0, "they're" to -3.0))
        )
        assertNull(offer)
    }

    // Degenerate guards: a set without the typed word, or smaller than two, yields no offer (and
    // never reaches scoreAll for the too-small case).
    @Test
    fun decideRealWordOffer_degenerateInputsReturnNull() {
        assertNull(
            ContextRescorer.decideRealWordOffer(
                typed = "zzz",
                confusionSet = listOf("there", "their"),   // typed not present
                scoreAll = batchScorer(mapOf("there" to 5.0, "their" to 0.0))
            )
        )
        assertNull(
            ContextRescorer.decideRealWordOffer(
                typed = "there",
                confusionSet = listOf("there"),            // size < 2
                scoreAll = { DoubleArray(it.size) }
            )
        )
    }

    // Score-count mismatch is a wiring bug -> fail loudly, same contract as decideCorrection.
    @Test(expected = IllegalArgumentException::class)
    fun decideRealWordOffer_scoreCountMismatch_throws() {
        ContextRescorer.decideRealWordOffer(
            typed = "there",
            confusionSet = listOf("there", "their"),
            scoreAll = { DoubleArray(1) }                  // wrong length on purpose
        )
    }
}
