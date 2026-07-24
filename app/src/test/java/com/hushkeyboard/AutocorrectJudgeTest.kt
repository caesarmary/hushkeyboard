package com.hushkeyboard

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * AutocorrectJudgeTest — Phase 5 characterization / judge harness.
 *
 * PURPOSE
 * -------
 * Phase 5 makes autocorrect context-aware (a transformer rescores candidates by
 * sentence context). Before any of that is built, this file does two things:
 *
 *   1. GROUP A — a REGRESSION NET. It pins down what the CURRENT, context-blind
 *      `Autocorrect.correct(String)` actually does today, run against the REAL
 *      bundled 50k word list (not a hand-built toy list). These tests PASS now.
 *      If Phase 5 work accidentally changes today's single-word behavior, these
 *      go red.
 *
 *   2. GROUP B — a YARDSTICK. The SAME cases, but asserting the DESIRED
 *      post-Phase-5 outcome. They are @Ignore'd so they do not break the build,
 *      but can be un-ignored one at a time to measure "did the context-rescorer
 *      actually help."
 *
 * This is a pure-JVM JUnit4 test (DEFINITION_OF_RIGHT Gate 8, Option 1 —
 * "extract pure logic"): `Autocorrect` is a plain Kotlin class with no Android
 * imports on its internal constructor path, so it runs on the JVM with no device
 * and no Robolectric. No new dependencies; JUnit4 only, matching AutocorrectTest.
 *
 * HOW THE REAL DICTIONARY IS LOADED
 * ---------------------------------
 * The 50k frequency-ordered, lowercase, one-word-per-line list ships as the asset
 * app/src/main/assets/wordlist_en.txt. On-device, the `Autocorrect(Context)`
 * constructor reads it from APK assets. Here we read the same file from disk and
 * feed its lines to the INTERNAL `Autocorrect(Collection<String>)` constructor —
 * the exact same parsing path (`addWords`) the production code uses.
 *
 * When Gradle runs unit tests, the working directory is the `app` module dir, so
 * the relative path resolves; the app/-prefixed path covers a run from the repo root.
 *
 * IMPORTANT HONESTY NOTE ABOUT CONTEXT
 * ------------------------------------
 * `Autocorrect.correct(input)` takes a SINGLE word and has NO sentence-context
 * parameter. Several Group B cases (real-word errors like there/their/they're,
 * form/from) CANNOT be fixed by any single-word API — they need the Phase-5
 * context-aware entry point that does not exist yet. We do NOT invent that API.
 * For those cases Group B is an @Ignore'd DESCRIPTIVE expectation: a comment
 * stating what context-aware autocorrect must achieve, plus an assertion that
 * stays honest by checking the only thing checkable today (e.g. that the desired
 * replacement is even representable in the dictionary). The comment on each such
 * test spells out the missing API explicitly.
 */
class AutocorrectJudgeTest {

    companion object {
        private lateinit var ac: Autocorrect

        /**
         * Loads the REAL wordlist asset once for all tests in this class.
         * Tries the Gradle-default working dir (app module) first, then the
         * app/-prefixed path for a run from the repo root.
         */
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
            ac = Autocorrect(lines)
        }
    }

    // -----------------------------------------------------------------------
    // SANITY: prove the real dictionary actually loaded.
    // (If this fails, every other result in the file is meaningless.)
    // -----------------------------------------------------------------------

    @Test
    fun sanity_realDictionaryLoaded() {
        // "helo" -> some real word proves the 50k list is present and parsed.
        assertNotNull(
            "Real dictionary failed to load: correct('helo') returned null",
            ac.correct("helo")
        )
        // A common word the list definitely contains is recognized as known.
        assertTrue("'friend' should be a known dictionary word", ac.isKnownWord("friend"))
        assertTrue("'there' should be a known dictionary word", ac.isKnownWord("there"))
    }

    // =======================================================================
    // GROUP A — CURRENT BEHAVIOR (regression net).
    //
    // Every assertion here was VERIFIED by simulating the exact Autocorrect
    // algorithm (OSA distance + frequency-rank tiebreak) against the real
    // wordlist_en.txt. These PASS today. Each carries the DESIRED Phase-5
    // outcome in a comment.
    // =======================================================================

    // --- A1. CONTROL: a classic misspelling the current corrector ALREADY fixes.
    // "recieve" -> "receive": the i/e are an adjacent transposition, which OSA
    // scores as distance 1. Proves the real dict is loaded AND that simple
    // dist-1 typos already work. Phase 5 must NOT regress this.
    @Test
    fun A1_control_recieve_correctsToReceive() {
        // DESIRED Phase-5 outcome: unchanged — still "receive".
        assertEquals("receive", ac.correct("recieve"))
    }

    // --- A2. CONTROL #2: "freind" -> "friend".
    // i/e transposition again, OSA distance 1. Already works today.
    @Test
    fun A2_control_freind_correctsToFriend() {
        // DESIRED Phase-5 outcome: unchanged — still "friend".
        assertEquals("friend", ac.correct("freind"))
    }

    // --- A3. NOTE / CONTROL: "definately" -> "definitely" ALREADY works today.
    // The task brief assumed this was a 2-edit typo the current code misses.
    // That is NOT true for this exact pair: "definately" vs "definitely" differ
    // only at one position (a vs i) — OSA distance 1 — so the current corrector
    // fixes it. We record this honestly as a control, not a failure. See A6 for
    // a typo that genuinely IS beyond distance 1.
    @Test
    fun A3_note_definately_alreadyCorrectsToDefinitely() {
        // DESIRED Phase-5 outcome: unchanged — still "definitely".
        assertEquals("definitely", ac.correct("definately"))
    }

    // --- A4. OVER-CORRECTION (the headline weakness): "helo" -> "help", NOT "hello".
    // Both "help" and "hello" are OSA distance 1 from "helo". The corrector ranks
    // by raw frequency only: "help" (rank ~265) is far more common than "hello"
    // (rank ~8333), so the frequency-blind ranker commits to the wrong word.
    // A human typing "helo" almost always means "hello". This is exactly the
    // "common-but-wrong word" failure Phase 5's context rescorer should fix.
    @Test
    fun A4_overcorrection_helo_picksHelpNotHello() {
        // DESIRED Phase-5 outcome: "hello" (context/intent-aware), see B4.
        assertEquals("help", ac.correct("helo"))
    }

    // --- A5. OVER-CORRECTION via 2-edit drift: "definatly" -> "defiantly".
    // The user meant "definitely". "defiantly" happens to sit at OSA distance 1
    // from "definatly" (and is a real word), so the corrector confidently emits
    // a real-but-wrong word. This is WORSE than returning null: it silently
    // changes the user's meaning.
    @Test
    fun A5_overcorrection_definatly_picksDefiantly() {
        // DESIRED Phase-5 outcome: "definitely", see B5.
        assertEquals("defiantly", ac.correct("definatly"))
    }

    // --- A6. 2-EDIT MISS: "tommorow" -> null.
    // The intended word "tomorrow" is more than one OSA edit away from "tommorow"
    // (missing 'r' AND a doubled 'm'), so the dist-1-only corrector finds no
    // candidate and gives up. A genuine example of the "only fixes edit-distance
    // 1" weakness (unlike "definately", which IS distance 1).
    @Test
    fun A6_twoEditMiss_tommorow_returnsNull() {
        // DESIRED Phase-5 outcome: "tomorrow", see B6.
        assertNull(ac.correct("tommorow"))
    }

    // --- A7. REAL-WORD ERROR, there/their/they're: "there" -> null.
    // "there" is itself a valid dictionary word, so `correct` short-circuits and
    // returns null — it can NEVER fix a real-word error. In "i think there going"
    // the intended word is "they're". The single-word API cannot see that.
    @Test
    fun A7_realWordError_there_returnsNull() {
        // DESIRED Phase-5 outcome: in context "i think ___ going", "they're".
        // Requires the context-aware entry point that does not exist yet. See B7.
        assertNull("'there' is a real word; context-blind corrector cannot touch it", ac.correct("there"))
    }

    // --- A8. REAL-WORD ERROR, form/from: both -> null.
    // "form" and "from" are both valid words; neither can be corrected to the
    // other by the current API regardless of intent.
    @Test
    fun A8_realWordError_formAndFrom_returnNull() {
        // DESIRED Phase-5 outcome: context decides (e.g. "fill in the ___" -> "form",
        // "a letter ___ home" -> "from"). Requires context API. See B8.
        assertNull("'form' is a real word; cannot be corrected", ac.correct("form"))
        assertNull("'from' is a real word; cannot be corrected", ac.correct("from"))
    }

    // --- A9. REAL-WORD ERROR, their/there confusion the OTHER direction.
    // "their" is valid -> null. Confirms the blind spot is symmetric.
    @Test
    fun A9_realWordError_their_returnsNull() {
        // DESIRED Phase-5 outcome: context picks among their/there/they're. See B9.
        assertNull(ac.correct("their"))
    }

    // =======================================================================
    // GROUP B — PHASE 5 TARGETS (yardstick).
    //
    // Same cases, asserting the DESIRED context-aware behavior. All @Ignore'd so
    // the build stays green. Un-ignore one at a time to measure progress once the
    // context rescorer exists.
    //
    // Two flavors:
    //   * Single-word targets (B5, B6): the CURRENT correct(String) signature is
    //     enough; the rescorer just needs to pick better candidates / widen the
    //     edit radius. These assert directly against correct(...).
    //   * Context-dependent targets (B4, B7, B8, B9): these CANNOT be expressed
    //     against correct(String) because it has no context parameter. We do NOT
    //     invent an API. Instead each is a DESCRIPTIVE expectation: the comment
    //     states the required new context-aware entry point and the exact desired
    //     mapping, and the assertion checks only the honest, checkable invariant
    //     (the desired word is representable / the failure is the one we expect).
    // =======================================================================

    // The override margin the stub scores must clear, same constant the production decision uses.
    private val margin = ContextRescorer.DEFAULT_OVERRIDE_MARGIN_NATS

    // A batch scorer from a table, positionally aligned to the candidate list it is handed.
    // Stands in for the model in decideCorrection here, exactly as ContextRescorerTest.batchScorer
    // does. Missing words score very low so they never win by accident. The REAL model's ranking
    // is NOT exercised in this JVM file — see the per-test comments below for where it is.
    private fun batchScorer(table: Map<String, Double>): (List<String>) -> DoubleArray =
        { cands -> DoubleArray(cands.size) { i -> table[cands[i]] ?: Double.NEGATIVE_INFINITY } }

    // --- B4. Target for A4: "helo" should correct to "hello", not "help".
    // STAYS @Ignore — this target does NOT fire on the bundled model + dictionary, and that is a
    // MEASURED limitation, not a bug. It is kept here as an honest yardstick of where the pipeline
    // falls short. The two measured causes (on-device, A52s, Session 71 —
    // see LlamaScoreCandidatesDeviceTest.decideCorrection_firesMeasuredTargets):
    //   1. REACHABILITY: "hello" is only the 41st-most-frequent ed<=2 neighbor of "helo" in the
    //      real wordlist, so it never enters the capped (DEFAULT_MAX_CANDIDATES = 8) candidate set —
    //      the rescorer never even gets to consider it.
    //   2. MODEL: even when "hello" is forced into the set, SmolLM2-135M ranks commoner words
    //      ("her"/"well"/"here") above it for greeting contexts. The 135M model prefers
    //      higher-frequency continuations.
    // So this is a dictionary-frequency + small-model limitation. We do NOT make it green; doing so
    // would require pretending one of the two measured causes does not hold. The on-device test
    // asserts the measured limitation explicitly (helo yields a valid in-dict word, just not "hello").
    @Ignore("MEASURED limitation (A52s, Session 71), not a wiring bug: 'hello' is the rank-41 ed<=2 neighbor of 'helo' so it is capped out of the candidate set, and SmolLM2-135M prefers commoner words even when it is forced in. See LlamaScoreCandidatesDeviceTest.")
    @Test
    fun B4_target_helo_correctsToHello() {
        assertEquals("hello", ac.correct("helo"))
    }

    // --- B5. Target for A5: "definatly" should context-correct to "definitely", not the
    // real-but-wrong fast pick "defiantly".
    //
    // HONESTY: the model cannot run in the JVM, so this does NOT assert
    // `ac.correct("definatly") == "definitely"` (that still returns the WRONG "defiantly" — it is
    // the context-blind fast pick, pinned in A5). Instead it verifies the two JVM-checkable halves
    // of the propose-then-rank design against the REAL dictionary:
    //   (a) REACHABILITY — "definitely" is actually a candidate the rescorer can reach: it is in
    //       ac.neighbors("definatly", 2). (Confirmed against the real wordlist: rank 4853, well
    //       above "defiantly" at rank 30365, so it is the #1-frequency ed<=2 neighbor.)
    //   (b) DECISION — given that candidate set and a scorer that prefers "definitely",
    //       ContextRescorer.decideCorrection overrides the wrong fast pick and returns "definitely".
    // The batchScorer here is a STUB standing in for the model. The model's REAL on-device ranking
    // (it scores "definitely" ~2.1 nats above "defiantly" in context, clearing the 1.5-nat margin)
    // is verified in LlamaScoreCandidatesDeviceTest.decideCorrection_firesMeasuredTargets, NOT here.
    // This target DOES fire end-to-end when typed on-device (the service refines the non-null fast
    // pick "defiantly").
    @Test
    fun B5_target_definatly_correctsToDefinitely() {
        val neighbors = ac.neighbors("definatly", 2)
        assertTrue(
            "'definitely' must be reachable as an ed<=2 neighbor of 'definatly'",
            neighbors.contains("definitely")
        )
        val winner = ContextRescorer.decideCorrection(
            typed = "definatly",
            neighbors = neighbors,
            fastPick = ac.correct("definatly"),                 // the wrong "defiantly"
            scoreAll = batchScorer(mapOf("definitely" to margin + 2.0, "defiantly" to 0.0))
        )
        assertEquals("definitely", winner)
    }

    // --- B6. Target for A6: "tommorow" should context-correct to "tomorrow".
    //
    // HONESTY: as with B5, the model cannot run in the JVM, so this asserts the two JVM-checkable
    // halves against the REAL dictionary:
    //   (a) REACHABILITY — "tomorrow" is in ac.neighbors("tommorow", 2). (Confirmed against the real
    //       wordlist: rank 3658, and it is the SOLE ed<=2 neighbor of "tommorow".)
    //   (b) DECISION — ac.correct("tommorow") is NULL (the typo is 2 edits from "tomorrow", beyond
    //       the fast corrector's ed-1 reach). Under the Session-75 guard the TYPED WORD itself is the
    //       incumbent and is scored alongside the neighbor; so "tomorrow" is returned only when it
    //       beats "tommorow"'s OWN score by the margin. The stub here makes it do so, exercising that
    //       override branch. The batchScorer is a STUB.
    //
    // END-TO-END HONESTY (Session 75): on the REAL model this does NOT fire. Measured on the A52s,
    // "tomorrow" scores ~0.38 nats BELOW "tommorow" in the "See you" context (the model prefers the
    // misspelling), so decideCorrection keeps "tommorow" — see
    // LlamaScoreCandidatesDeviceTest.decideCorrection_firesMeasuredTargets (pinned as a kept word) and
    // .decideCorrection_ed2OverCorrectionBattery. Because the only ed-2 cases that DO clear the margin
    // are rare->common substitutions (over-corrections), Session 75 DISABLED the ed-2 auto-correct path
    // in the service. So "tommorow" does not change when typed (verified live), and this stub test only
    // pins the decision-core override branch, not a shipped behavior.
    @Test
    fun B6_target_tommorow_correctsToTomorrow() {
        val neighbors = ac.neighbors("tommorow", 2)
        assertTrue(
            "'tomorrow' must be reachable as an ed<=2 neighbor of 'tommorow'",
            neighbors.contains("tomorrow")
        )
        assertNull(
            "precondition: 'tommorow' has no ed-1 fast pick (2 edits from 'tomorrow')",
            ac.correct("tommorow")
        )
        val winner = ContextRescorer.decideCorrection(
            typed = "tommorow",
            neighbors = neighbors,
            fastPick = ac.correct("tommorow"),                  // null -> typed word is the incumbent
            // Typed word IS scored under the guard; stub gives the neighbor a margin-clearing lead.
            scoreAll = batchScorer(mapOf("tommorow" to 0.0, "tomorrow" to margin + 1.0))
        )
        assertEquals("tomorrow", winner)
    }

    // --- B7. Target for A7: real-word error there -> they're, IN CONTEXT.
    //
    // REQUIRED NEW API (does not exist yet — do not assume this signature is
    // final; it is a placeholder for the Phase-5 context entry point):
    //
    //     fun correctInContext(precedingText: String, word: String): String?
    //
    // Desired mapping: correctInContext("i think", "there") == "they're"
    // (the sentence "i think there going" -> "i think they're going").
    //
    // We canNOT call that yet. The honest checkable invariant today is the ROOT
    // CAUSE of the blind spot: the contraction "they're" is not even
    // representable in the current dictionary (the asset is letters-only; the
    // parser drops any token with an apostrophe). So context-rescoring among
    // their/there/they're will ALSO require a contractions source. This assertion
    // documents that gap.
    @Ignore("Phase 5 target: context-rescorer must satisfy this")
    @Test
    fun B7_target_there_to_theyre_inContext() {
        // Today: "they're" cannot come out of the dictionary at all (apostrophe
        // filtered). Phase 5 must supply contraction candidates AND context
        // ranking. When the context API lands, replace the assertion below with:
        //   assertEquals("they're", ac.correctInContext("i think", "there"))
        assertFalse(
            "PRECONDITION GAP: 'they're' is not representable in the current " +
                "dictionary (apostrophes filtered). Phase 5 needs a contraction " +
                "candidate source before context ranking can pick it.",
            ac.isKnownWord("they're")
        )
    }

    // --- B8. Target for A8: form/from disambiguation IN CONTEXT.
    //
    // REQUIRED NEW API (placeholder, same as B7):
    //     fun correctInContext(precedingText: String, word: String): String?
    //
    // Desired mappings:
    //   correctInContext("please fill in the", "from") == "form"
    //   correctInContext("a letter", "form")          == "from"
    //
    // Both "form" and "from" ARE in the dictionary (unlike "they're"), so the
    // only thing missing is the context signal. The honest checkable invariant
    // today is that both words exist (so the rescorer has both candidates to
    // choose between) and that the current single-word API is correctly blind.
    @Ignore("Phase 5 target: context-rescorer must satisfy this")
    @Test
    fun B8_target_formFrom_disambiguatedInContext() {
        // Both candidates representable -> the only Phase-5 work is the context
        // ranking itself. When the context API lands, replace with:
        //   assertEquals("form", ac.correctInContext("please fill in the", "from"))
        //   assertEquals("from", ac.correctInContext("a letter", "form"))
        assertTrue("'form' must be a candidate the rescorer can choose", ac.isKnownWord("form"))
        assertTrue("'from' must be a candidate the rescorer can choose", ac.isKnownWord("from"))
    }

    // --- B9. Target for A9: their/there/they're three-way disambiguation.
    //
    // REQUIRED NEW API (placeholder, same as B7):
    //     fun correctInContext(precedingText: String, word: String): String?
    //
    // Desired (illustrative) mappings:
    //   correctInContext("over", "their")       == "there"   ("over there")
    //   correctInContext("this is", "there")    == "their"   (less common, context-driven)
    //   correctInContext("i think", "there")    == "they're" ("they're going")
    //
    // "their" and "there" are in the dictionary; "they're" is NOT (see B7). This
    // case therefore needs BOTH a contraction source and context ranking.
    @Ignore("Phase 5 target: context-rescorer must satisfy this")
    @Test
    fun B9_target_theirThereTheyre_threeWay() {
        // When the context API + contraction source land, replace with the three
        // correctInContext assertions above.
        assertTrue("'their' present as a candidate", ac.isKnownWord("their"))
        assertTrue("'there' present as a candidate", ac.isKnownWord("there"))
        assertFalse(
            "PRECONDITION GAP: 'they're' missing from dictionary (apostrophe filtered)",
            ac.isKnownWord("they're")
        )
    }
}
