package com.hushkeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * EditCostTest — Phase 5 Session 78.
 *
 * Pins the one property the edit-cost prior exists to provide: a genuine typo (transposition,
 * doubled/missing letter, adjacent-key slip) costs FAR less than an arbitrary far-key substitution
 * to an unrelated word. These are the real harness cases (good fixes vs over-corrections), checked
 * here on the JVM with no model and no device (DEFINITION_OF_RIGHT Gate 8 Option 1).
 */
class EditCostTest {

    @Test
    fun identityCostsNothing() {
        assertEquals(0.0, EditCost.cost("receive", "receive"), 0.0)
    }

    @Test
    fun transpositionIsCheap() {
        // freind -> friend is a single adjacent-letter swap.
        assertEquals(EditCost.TRANSPOSE, EditCost.cost("freind", "friend"), 1e-9)
    }

    @Test
    fun doubledLetterIsCheap() {
        // tomorow -> tomorrow inserts a doubled 'r' (the cheap doubled-letter slip).
        assertEquals(EditCost.DOUBLE_GAP, EditCost.cost("tomorow", "tomorrow"), 1e-9)
    }

    @Test
    fun adjacentKeySubstitutionIsCheaperThanFar() {
        // 'd'->'s' are neighbours on QWERTY; 'd'->'p' are not.
        assertTrue(EditCost.cost("dad", "sad") < EditCost.cost("dad", "pad"))
        assertEquals(EditCost.ADJ_SUB, EditCost.cost("dad", "sad"), 1e-9)
        assertEquals(EditCost.FAR_SUB, EditCost.cost("dad", "pad"), 1e-9)
    }

    @Test
    fun overCorrectionsAreExpensive() {
        // The Session-77 ed-2 over-corrections must each cost clearly MORE than a genuine single-slip
        // typo (transposition/doubled letter = 0.5), so the bar in ContextRescorer (margin + cost)
        // rises enough to keep them from overriding the typed word. The far-key-substitution cases
        // (yeeted->seemed etc.) cost ~4.4; stdout->strut is the cheapest at ~2.1 because d->r is a
        // diagonally-ADJACENT key slip plus a dropped letter — genuinely the most typo-like of the
        // five, and correspondingly the one most likely to still need the model's help to reject.
        val genuineTypo = EditCost.cost("freind", "friend")          // 0.5
        for ((typed, bad) in listOf(
            "yeeted" to "seemed",
            "async" to "assoc",
            "stderr" to "steers",
            "regex" to "roger",
            "stdout" to "strut",
        )) {
            val c = EditCost.cost(typed, bad)
            assertTrue(
                "$typed -> $bad cost $c should exceed a genuine single-slip typo by a clear margin",
                c > genuineTypo + 1.0
            )
        }
    }

    @Test
    fun costIsSymmetricForSubstitutionsAndSwaps() {
        // Sanity: the metric does not depend on argument order for pure substitutions.
        assertEquals(EditCost.cost("dad", "sad"), EditCost.cost("sad", "dad"), 1e-9)
    }
}
