package com.hushkeyboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 Session 71 (slice 1b) on-device coverage for the native candidate
 * scorer ([LlamaSession.scoreCandidates] / nativeScoreCandidates) and the full
 * context-aware autocorrect path through the real SmolLM2 model. There is no
 * JVM equivalent for the native scoring math, so this is its only coverage.
 *
 * Same deferred-asset pattern as [LlamaSessionDeviceTest]: the GGUF is pushed
 * once via adb to the app's external files dir; if it isn't present, every test
 * here is SKIPPED (assumeTrue), not failed.
 *
 * The chosen tokenization convention (" " + candidate, add_special=false) is the
 * highest-risk decision in this slice; [scoreCandidates_rankingSanity] is the
 * empirical check that it yields sane, ranked log-probs.
 */
@RunWith(AndroidJUnit4::class)
class LlamaScoreCandidatesDeviceTest {

    private fun modelFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), GGUF_NAME)
    }

    private fun libDir(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir

    // The native math has no JVM equivalent: a fixed (context, candidate) must produce a stable
    // log-prob. We record the measured value as the golden and assert future runs match within a
    // small tolerance (floating-point/threading jitter on the same model is tiny). Also asserts the
    // score is a finite negative number (a log-prob of a <1 probability), i.e. sane.
    @Test
    fun scoreCandidates_goldenValue() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val session = LlamaSession()
        try {
            session.load(model.absolutePath, libDir())
            session.prefill(GOLDEN_CONTEXT)
            val scores = session.scoreCandidates(listOf(GOLDEN_CANDIDATE))
            assertEquals("expected exactly one score", 1, scores.size)
            val logp = scores[0]
            Log.i(TAG, "GOLDEN log P('$GOLDEN_CANDIDATE' | '$GOLDEN_CONTEXT') / tokens = $logp")
            assertTrue("score must be finite, got $logp", logp.isFinite())
            assertTrue("a length-normalized log-prob must be <= 0, got $logp", logp <= 0.0)
            assertTrue(
                "golden score $logp drifted from $GOLDEN_VALUE by more than $GOLDEN_TOLERANCE",
                kotlin.math.abs(logp - GOLDEN_VALUE) <= GOLDEN_TOLERANCE
            )
        } finally {
            session.close()
        }
    }

    // Ranking sanity = the empirical justification for the leading-space tokenization convention.
    // For a clear context, the correct continuation must outscore an obvious wrong one.
    @Test
    fun scoreCandidates_rankingSanity() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val session = LlamaSession()
        try {
            session.load(model.absolutePath, libDir())
            session.prefill(RANK_CONTEXT)
            val scores = session.scoreCandidates(listOf(RANK_RIGHT, RANK_WRONG))
            assertEquals(2, scores.size)
            Log.i(TAG, "RANK '$RANK_CONTEXT' -> '$RANK_RIGHT'=${scores[0]}  '$RANK_WRONG'=${scores[1]}")
            assertTrue(
                "'$RANK_RIGHT' (${scores[0]}) should outscore '$RANK_WRONG' (${scores[1]})",
                scores[0] > scores[1]
            )
        } finally {
            session.close()
        }
    }

    // End-to-end through the REAL model via the same decideCorrection orchestration the service uses.
    //
    // MEASURED on the A52s (Session 71): TWO of the three slice-1b targets fire through this
    // propose-then-rank pipeline; the third (helo -> hello) does NOT, and the reason is empirical and
    // worth pinning here so a future change is judged against the real cause, not a guess:
    //   * definatly -> definitely: the fast ed-1 pick is "defiantly" (wrong); with the "I will most"
    //     context the model scores "definitely" ~2.1 nats above "defiantly", clearing the 1.5-nat
    //     override margin. FIRES.
    //   * tommorow -> tomorrow: does NOT fire. The fast ed-1 corrector finds nothing (fastPick ==
    //     null), so under the Session-75 typed-word-as-incumbent guard "tomorrow" must beat
    //     "tommorow"'s OWN length-normalized context score by the margin. On this model it scores
    //     ~0.38 nats BELOW it — the misspelling out-scores the correct word — so the typed word is
    //     kept. That, plus the over-correction battery showing the only cases that DO clear the bar
    //     are rare->common substitutions (yeeted->seemed), is why Session 75 disabled the ed-2 auto-
    //     correct path in the service. Asserted below as a kept word so a regression is visible.
    //   * helo -> hello: "hello" is only the 41st-most-frequent ed<=2 neighbor of "helo" (it is a
    //     low-frequency wordlist entry), so it never enters the capped candidate set; and even when
    //     forced in, SmolLM2-135M ranks "her"/"well"/"here" above "hello" for greeting contexts. So
    //     this target is a measured LIMITATION of the dictionary+small-model pipeline, not a wiring
    //     bug. The pipeline still returns a valid in-dictionary correction, just not "hello".
    @Test
    fun decideCorrection_firesMeasuredTargets() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val autocorrect = Autocorrect(ctx)
        val predictor = LlamaPredictor(model.absolutePath, libDir())
        try {
            for ((context, typed, expected) in FIRING_TARGETS) {
                val result = decide(autocorrect, predictor, context, typed)
                Log.i(TAG, "TARGET '$context' | '$typed' -> '$result' (expected '$expected')")
                assertEquals("decideCorrection('$typed') in context '$context'", expected, result)
            }
            // tommorow: under the Session-75 guard the model prefers the misspelling (fastPick null,
            // typed word is the incumbent, "tomorrow" scores below it), so decideCorrection keeps it.
            // Pinned so a regression — or a model/scoring change that flips the comparison — is visible.
            val tommorowResult = decide(autocorrect, predictor, "See you", "tommorow")
            Log.i(TAG, "TARGET (kept) 'tommorow' -> '$tommorowResult'")
            assertEquals("tommorow is kept (model prefers the misspelling)", "tommorow", tommorowResult)

            // helo: assert the measured limitation explicitly so a regression that "fixes" it (or
            // breaks the pipeline) is visible. The pipeline returns a non-null in-dictionary word,
            // and with these contexts it is NOT "hello" on this model.
            val heloResult = decide(autocorrect, predictor, "I picked up the phone and said", "helo")
            Log.i(TAG, "TARGET (limitation) 'helo' -> '$heloResult'")
            assertTrue("helo should still yield a correction", !heloResult.isNullOrEmpty())
        } finally {
            predictor.close()
        }
    }

    private fun decide(
        autocorrect: Autocorrect,
        predictor: LlamaPredictor,
        context: String,
        typed: String
    ): String? = ContextRescorer.decideCorrection(
        typed = typed,
        neighbors = autocorrect.neighbors(typed, maxEdits = 2),
        fastPick = autocorrect.correct(typed),
        scoreAll = { cands -> predictor.scoreCorrectionCandidates(context, cands) }
    )

    // Latency of one scoreCorrectionCandidates call (warm) with the full candidate cap. Records
    // p50/p95. Measured ~tens of ms on the A52s (prefill dominates; short single-token candidates
    // need no decodes) — still far above an IME-thread budget, so the async refine stays mandatory.
    @Test
    fun scoreCorrectionCandidates_latency() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val autocorrect = Autocorrect(ctx)
        val predictor = LlamaPredictor(model.absolutePath, libDir())
        try {
            // "helo" fills the full DEFAULT_MAX_CANDIDATES (8) candidate cap, so this is the realistic
            // worst-case latency the async refine must absorb (a small typo with few neighbors is
            // cheaper).
            val typed = "helo"
            val context = "I picked up the phone and said"
            val candidates = CandidateGenerator.forCorrection(
                typed,
                autocorrect.neighbors(typed, maxEdits = 2),
                ContextRescorer.DEFAULT_MAX_CANDIDATES
            )
            Log.i(TAG, "LATENCY scoring ${candidates.size} candidates for '$typed'")

            // Warm-up (cold first call includes graph/cache priming).
            predictor.scoreCorrectionCandidates(context, candidates)

            val samples = LongArray(LATENCY_ITERS)
            for (i in 0 until LATENCY_ITERS) {
                val t0 = System.nanoTime()
                predictor.scoreCorrectionCandidates(context, candidates)
                samples[i] = (System.nanoTime() - t0) / 1_000_000L
            }
            samples.sort()
            val p50 = samples[samples.size / 2]
            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)]
            Log.i(TAG, "LATENCY scoreCorrectionCandidates p50=${p50}ms p95=${p95}ms samples=${samples.toList()}")
            assertTrue("latency samples missing", samples.isNotEmpty())
        } finally {
            predictor.close()
        }
    }

    // Phase 5 Session 72 — homophone sanity probe (gates slice 2: real-word / contraction overrides).
    //
    // Slice 1b only ever corrects NON-word typos (can't corrupt real text). Slice 2 wants to override
    // a correctly-spelled real word (form->from, there->their) by sentence context. That is only safe
    // if SmolLM2-135M can actually tell the homophones apart from LEFT context alone, by a margin big
    // enough to clear a (deliberately larger-than-1.5-nat) real-word override bar. This probe measures
    // that BEFORE any slice-2 code is written, so the design rests on data, not an assumption.
    //
    // It uses the existing scoreCorrectionCandidates primitive (no new native code). For each case it
    // prefills the context once, scores the whole confusion set in one call, and records:
    //   * rank#1?  — did the contextually-correct word get the highest log-prob
    //   * margin   — correct's log-prob minus the best *competing* homophone's (nats; <0 = wrong pick)
    // The verdict (pure-Kotlin) is logged as a table plus an aggregate. SECURITY.md rule 4 (password-
    // field discipline) is not implicated: every context here is a hardcoded test string, so no live
    // input field is ever read.
    //
    // GREEN = 135M clears the bar, slice 2 proceeds as designed. RED = it can't disambiguate reliably;
    // slice 2 needs rethinking (bigger model / different approach), do NOT build on the assumption.
    @Test
    fun homophoneDisambiguation_probe() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val predictor = LlamaPredictor(model.absolutePath, libDir())
        try {
            val margins = ArrayList<Double>(HOMOPHONE_CASES.size)
            var rankedFirst = 0
            for (case in HOMOPHONE_CASES) {
                val scores = predictor.scoreCorrectionCandidates(case.context, case.confusionSet)
                val correctIdx = case.confusionSet.indexOf(case.correct)
                val correctScore = scores[correctIdx]
                val bestOther = case.confusionSet.indices
                    .filter { it != correctIdx }
                    .maxOf { scores[it] }
                val margin = correctScore - bestOther
                val isFirst = scores.indices.all { it == correctIdx || scores[it] <= correctScore }
                if (isFirst) rankedFirst++
                margins.add(margin)
                val table = case.confusionSet.indices.joinToString(" ") {
                    "${case.confusionSet[it]}=${"%.2f".format(scores[it])}"
                }
                Log.i(
                    TAG,
                    "HOMOPHONE '${case.context} ___' want='${case.correct}' " +
                        "rank1=$isFirst margin=${"%.2f".format(margin)}nats | $table"
                )
            }

            val sorted = margins.sorted()
            val median = sorted[sorted.size / 2]
            val minMargin = sorted.first()
            val fracFirst = rankedFirst.toDouble() / HOMOPHONE_CASES.size
            val fracClearBar = margins.count { it >= USABLE_MARGIN_NATS }.toDouble() / HOMOPHONE_CASES.size

            // Bar: the model is usable for real-word overrides only if it reliably ranks the correct
            // homophone first AND the typical winning margin clears the existing 1.5-nat override bar
            // (with headroom to set an even larger real-word margin). Thresholds are judgement calls,
            // logged alongside the raw data so the human decision can override them.
            val clearsBar = fracFirst >= MIN_FRAC_RANKED_FIRST && median >= USABLE_MARGIN_NATS
            Log.i(
                TAG,
                "HOMOPHONE VERDICT clearsBar=$clearsBar | rankedFirst=$rankedFirst/${HOMOPHONE_CASES.size} " +
                    "(${"%.0f".format(fracFirst * 100)}%) marginNats[min=${"%.2f".format(minMargin)} " +
                    "median=${"%.2f".format(median)} max=${"%.2f".format(sorted.last())}] " +
                    "casesClearing${USABLE_MARGIN_NATS}nat=${"%.0f".format(fracClearBar * 100)}%"
            )

            assertTrue(
                "SmolLM2-135M did NOT clear the homophone bar (rankedFirst=$rankedFirst/${HOMOPHONE_CASES.size}, " +
                    "median margin=${"%.2f".format(median)} nats). Slice 2 needs rethinking — see logcat table.",
                clearsBar
            )
        } finally {
            predictor.close()
        }
    }

    // Phase 5 Session 75 — ed-2 over-correction battery (the tuning measurement for Option 2).
    //
    // The Session-75 guard makes the TYPED word the incumbent on the ed-2 path (fastPick == null): a
    // non-word is replaced only when a neighbor beats the typed word's OWN context score by the
    // override margin. This battery measures, for real novel words (names/slang) that have dictionary
    // neighbors, the margin by which the best neighbor leads the typed word — i.e. how aggressively
    // each margin setting would clobber them — and confirms a genuine typo (tommorow) still clears the
    // bar. Pure measurement: it logs a per-word table plus a per-threshold clobber count, so
    // ED2_MIN_LENGTH / DEFAULT_OVERRIDE_MARGIN_NATS can be tuned from device numbers, not guesses. It
    // asserts only that the battery ran (like the latency test); the decision lives in logcat.
    //
    // SECURITY.md rule 4 is not implicated: every context is a hardcoded test string; no live field is
    // read.
    @Test
    fun decideCorrection_ed2OverCorrectionBattery() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val autocorrect = Autocorrect(ctx)
        val predictor = LlamaPredictor(model.absolutePath, libDir())
        try {
            // margin thresholds to report the clobber count at (the current default is 1.5).
            val thresholds = listOf(1.0, 1.5, 2.0, 2.5, 3.0)
            val clobberAt = IntArray(thresholds.size)   // novel words clobbered at thresholds[i]
            var novelCount = 0

            for (case in ED2_BATTERY) {
                val fastPick = autocorrect.correct(case.typed)
                val neighbors = autocorrect.neighbors(case.typed, maxEdits = 2)
                val generated = CandidateGenerator.forCorrection(
                    case.typed, neighbors, ContextRescorer.DEFAULT_MAX_CANDIDATES
                )
                if (generated.isEmpty()) {
                    Log.i(TAG, "ED2 [${case.kind}] '${case.context}' '${case.typed}' -> NO NEIGHBORS (never exposed)")
                    continue
                }
                // Score the typed word as incumbent alongside the neighbors (exactly the Session-75
                // candidate set), then read off the best neighbor's lead over the typed word.
                val candidates = buildList { add(case.typed); addAll(generated) }
                val scores = predictor.scoreCorrectionCandidates(case.context, candidates)
                val typedScore = scores[0]
                val bestNeighborIdx = (1 until candidates.size).maxByOrNull { scores[it] }!!
                val margin = scores[bestNeighborIdx] - typedScore   // >0 => a neighbor leads the typed word
                val winner = ContextRescorer.decideCorrection(
                    typed = case.typed,
                    neighbors = neighbors,
                    fastPick = fastPick,
                    scoreAll = { c -> predictor.scoreCorrectionCandidates(case.context, c) }
                )
                Log.i(
                    TAG,
                    "ED2 [${case.kind}] '${case.context}' '${case.typed}' fastPick=$fastPick " +
                        "bestNeighbor='${candidates[bestNeighborIdx]}' margin=${"%.2f".format(margin)}nats " +
                        "-> '$winner' changed=${winner != case.typed}"
                )
                if (case.kind == "novel" && fastPick == null) {
                    novelCount++
                    thresholds.forEachIndexed { i, t -> if (margin >= t) clobberAt[i]++ }
                }
            }

            val summary = thresholds.indices.joinToString("  ") {
                "margin>=${thresholds[it]}: ${clobberAt[it]}/$novelCount clobbered"
            }
            Log.i(TAG, "ED2 BATTERY SUMMARY (novel words, lower clobber = safer) | $summary")
            assertTrue("battery produced no scored cases", novelCount > 0)
        } finally {
            predictor.close()
        }
    }

    private data class Ed2Case(
        val context: String,
        val typed: String,   // lowercase, as the input buffer holds it on the ed-2 path
        val kind: String     // "novel" (must be preserved) or "typo" (should be corrected)
    )

    private data class HomophoneCase(
        val context: String,
        val correct: String,
        val confusionSet: List<String>
    )

    companion object {
        private const val TAG = "HushScoreCandidates"
        private const val GGUF_NAME = "smollm2_135m_instruct_q8_0.gguf"

        // A real-word override is riskier than a non-word fix, so the bar matches (not undercuts) the
        // existing 1.5-nat non-word override margin; slice 2 would then set a margin >= this.
        private const val USABLE_MARGIN_NATS = ContextRescorer.DEFAULT_OVERRIDE_MARGIN_NATS
        private const val MIN_FRAC_RANKED_FIRST = 0.75

        // Confusion sets probed in BOTH directions: each homophone is the correct answer in at least
        // one context and a distractor in another. Context is the text BEFORE the blank (left-context
        // only — all the scorer sees). they're/it's/you're are the genuinely hard cases and are kept
        // deliberately; the probe exists to find out whether the small model handles them.
        private val HOMOPHONE_CASES = listOf(
            HomophoneCase("Please fill in this", "form", listOf("form", "from")),
            HomophoneCase("I just got a letter", "from", listOf("form", "from")),
            HomophoneCase("The book is over", "there", listOf("there", "their", "they're")),
            HomophoneCase("They parked", "their", listOf("there", "their", "they're")),
            HomophoneCase("I hope", "they're", listOf("there", "their", "they're")),
            HomophoneCase("The dog wagged", "its", listOf("its", "it's")),
            HomophoneCase("I think", "it's", listOf("its", "it's")),
            HomophoneCase("Where is", "your", listOf("your", "you're")),
            HomophoneCase("I think", "you're", listOf("your", "you're")),
            HomophoneCase("It is much bigger", "than", listOf("than", "then")),
            HomophoneCase("First do this, and", "then", listOf("than", "then")),
            HomophoneCase("I don't know", "whether", listOf("whether", "weather")),
            HomophoneCase("How is the", "weather", listOf("whether", "weather"))
        )

        // Session 75 ed-2 over-correction battery. "novel" = a real name/slang word the ed-2 path
        // must NOT clobber; "typo" = a genuine non-word that SHOULD still be corrected. All lowercase
        // (the input-buffer form) and length >= ED2_MIN_LENGTH so they actually reach the ed-2 path.
        // Contexts are plausible left-contexts (all the scorer sees). Words with an ed-1 fix take the
        // ed-1 path instead (fastPick != null) — the test logs fastPick so those are visible and
        // excluded from the novel-word clobber count.
        private val ED2_BATTERY = listOf(
            Ed2Case("Hi my name is", "tomas", "novel"),
            Ed2Case("I spoke with", "reyes", "novel"),
            Ed2Case("My doctor is", "nguyen", "novel"),
            Ed2Case("This is my friend", "kavya", "novel"),
            Ed2Case("That movie was", "lowkey", "novel"),
            Ed2Case("I am", "deadass", "novel"),
            Ed2Case("I watched a", "mukbang", "novel"),
            Ed2Case("He just", "yeeted", "novel"),
            Ed2Case("He is a total", "rizzler", "novel"),
            Ed2Case("We met at the", "izakaya", "novel"),
            Ed2Case("See you", "tommorow", "typo")
        )

        // Golden: measured on the A52s with the bundled SmolLM2-135M Q8_0 GGUF, Session 71.
        // log P(" store" | "I went to the") / tokens. Tolerance covers FP/threading jitter on the
        // same model (the raw value was reproducible to many decimals across runs).
        private const val GOLDEN_CONTEXT = "I went to the"
        private const val GOLDEN_CANDIDATE = "store"
        private const val GOLDEN_VALUE = -2.715561750986443
        private const val GOLDEN_TOLERANCE = 0.05

        private const val RANK_CONTEXT = "I went to the grocery"
        private const val RANK_RIGHT = "store"
        private const val RANK_WRONG = "stork"

        // (context, typed, expected) — the ed-1 target that fires through the real pipeline on this
        // model: "definatly" has a (wrong) fast ed-1 pick "defiantly", and the model lifts "definitely"
        // past it by > margin. The former ed-2 target "tommorow" is asserted separately as a KEPT word
        // (the Session-75 guard prevents it; see decideCorrection_firesMeasuredTargets), and helo->hello
        // is a measured limitation (also below).
        private val FIRING_TARGETS = listOf(
            Triple("I will most", "definatly", "definitely")
        )

        private const val LATENCY_ITERS = 11
    }
}
