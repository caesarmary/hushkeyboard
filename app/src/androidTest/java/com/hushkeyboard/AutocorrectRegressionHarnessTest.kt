package com.hushkeyboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 Session 77 — the synthetic typo regression harness (improvement-menu Option 1).
 *
 * THE INSTRUMENT. It runs the REAL autocorrect decision path over a synthetic corpus and prints
 * two headline numbers to logcat:
 *   * FIX RATE          — % of genuine typos that get corrected to the intended word.
 *   * OVER-CORRECTION    — % of valid words (rare words, names, slang, code terms) that get
 *                          wrongly CHANGED. This is the number that would have caught the ed-2
 *                          over-correction before it shipped (Session 75).
 *
 * Why this is a DEVICE (instrumented) test, not a JVM unit test: the decision depends on the
 * model's per-candidate context score, and the model (SmolLM2-135M via llama.cpp) only runs on
 * the phone. A JVM test would have to FAKE the scores, and a fake-score test cannot reproduce a
 * real-model misjudgement — which is exactly what the ed-2 regression was. So the only honest
 * instrument runs the real model. Still 100% offline: no network, ever (SECURITY.md rule 1).
 *
 * It calls the SAME pure-Kotlin decision core the service uses
 * (HushKeyboardService.scheduleAutocorrectRefine -> ContextRescorer.decideCorrection with
 * predictor.scoreCorrectionCandidates), so it tests the real logic, not a copy.
 *
 * SECURITY.md: every context/word below is a HARDCODED synthetic test string. No live input
 * field is ever read (rule 4 untouched), nothing logged is real user keystrokes (rule 3). This is
 * androidTest code — it never ships in the release APK.
 *
 * Same deferred-asset pattern as the other device tests: if the GGUF isn't pushed, every test is
 * SKIPPED (assumeTrue), not failed.
 */
@RunWith(AndroidJUnit4::class)
class AutocorrectRegressionHarnessTest {

    private fun modelFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), GGUF_NAME)
    }

    private fun libDir(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir

    // The real decision, exactly as the service runs it (HushKeyboardService.scheduleAutocorrectRefine):
    // dictionary proposes (Autocorrect.neighbors + Autocorrect.correct), model ranks
    // (predictor.scoreCorrectionCandidates), ContextRescorer.decideCorrection decides — now with the
    // Session-78 levers wired in exactly as the service wires them: the learned-words shield
    // ([isLearned]) and the edit-cost / typo-likelihood prior ([EditCost]).
    private fun decide(
        autocorrect: Autocorrect,
        predictor: LlamaPredictor,
        context: String,
        typed: String,
        isLearned: (String) -> Boolean
    ): String? = ContextRescorer.decideCorrection(
        typed = typed,
        neighbors = autocorrect.neighbors(typed, maxEdits = 2),
        fastPick = autocorrect.correct(typed, isLearned),
        scoreAll = { cands -> predictor.scoreCorrectionCandidates(context, cands) },
        isLearned = isLearned,
        editCost = { cand -> EditCost.cost(typed, cand) }
    )

    @Test
    fun regressionHarness_reportsFixAndOverCorrectionRates() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val autocorrect = Autocorrect(ctx)
        val predictor = LlamaPredictor(model.absolutePath, libDir())
        // Session-78 learned-words shield, exercised with SYNTHETIC seed words (no live field, no
        // real store — SECURITY rule 4/5 untouched). These stand in for names the user has taught the
        // keyboard. Seeding ONLY the names keeps the attribution clean: the ed-1 over-corrections
        // (tomas->thomas, priya->prima) are fixed by THIS shield, while the ed-2 over-corrections
        // (yeeted, async, stderr, regex, stdout) are left UNlearned so they can only be saved by the
        // edit-cost prior — proving each lever independently.
        val learnedSet = setOf("tomas", "priya", "reyes", "nguyen")
        val isLearned: (String) -> Boolean = { learnedSet.contains(it.lowercase()) }
        try {
            // --- TYPO set: genuine non-words that SHOULD be fixed. ---
            var fixed = 0
            for ((context, typed, expected) in TYPOS) {
                val result = decide(autocorrect, predictor, context, typed, isLearned)
                val ok = result == expected
                if (ok) fixed++
                Log.i(TAG, "TYPO   '$context' '$typed' -> '$result' (want '$expected') ${if (ok) "FIXED" else "miss"}")
            }
            val fixRate = fixed.toDouble() / TYPOS.size

            // --- LEAVE-ALONE set: valid words that must NOT be changed. ---
            var overCorrected = 0
            for ((context, typed) in LEAVE_ALONE) {
                val result = decide(autocorrect, predictor, context, typed, isLearned)
                // A word with no dictionary neighbours is never exposed to the model -> kept (safe).
                val changed = result != null && result != typed
                if (changed) overCorrected++
                val shown = result ?: "(no correction)"
                Log.i(TAG, "LEAVE  '$context' '$typed' -> '$shown' ${if (changed) "*** CLOBBERED ***" else "kept"}")
            }
            val overCorrectionRate = overCorrected.toDouble() / LEAVE_ALONE.size

            // The two headline numbers.
            Log.i(
                TAG,
                "HARNESS RESULT  fixRate=$fixed/${TYPOS.size} (${pct(fixRate)})  " +
                    "overCorrection=$overCorrected/${LEAVE_ALONE.size} (${pct(overCorrectionRate)})"
            )

            // Guard-rail only. The real signal is the two logged numbers; this assert just fails
            // loudly on a gross over-correction regression. The threshold is a PLACEHOLDER — set it
            // tighter from the first device baseline (same philosophy as the existing batteries,
            // which assert only that they ran).
            assertTrue("harness produced no typo cases", TYPOS.isNotEmpty())
            assertTrue("harness produced no leave-alone cases", LEAVE_ALONE.isNotEmpty())
            assertTrue(
                "over-correction rate ${pct(overCorrectionRate)} exceeded guard-rail " +
                    "${pct(MAX_OVER_CORRECTION)} — a valid word is being clobbered; see logcat 'CLOBBERED' lines",
                overCorrectionRate <= MAX_OVER_CORRECTION
            )
        } finally {
            predictor.close()
        }
    }

    // --- Phase 5 Session 81: the offline spatial-prior measurement gate. ------------------------
    //
    // Same real decision core as decide() above, but the SAME scoreAll lambda the service injects is
    // WRAPPED to add a static-Gaussian-per-key spatial term to each candidate's model score
    // (modelScore + spatialFit). decideCorrection, the margin, the edit-cost prior and the learned
    // shield all run exactly as shipped — only the injected score changes — so the spatial number is
    // directly comparable to the baseline. [taps] is one synthetic (x,y) per typed character; when
    // [spatialOn] is false the lambda returns the raw model scores (= today's baseline, no spatial).
    //
    // HONESTY CAVEAT — read before trusting any number this prints. The synthetic taps are generated
    // from the SAME hardcoded key geometry the prior scores against, so this measures the BEST-CASE
    // CEILING of a spatial prior, not its real-world delivered value. A clear NO-GO is conclusive (it
    // can't help even when the prior perfectly matches how the taps were made); a GO is necessary-
    // but-not-sufficient (real taps add the geometry-staleness + per-word buffer-alignment risk of
    // Session 80 part (c) that this offline sim cannot see). And the physics is honest, not rigged: a
    // tap that REGISTERED as the typed key is by definition nearest that key, so spatial can never
    // push toward the intended letter — at most it makes switching cheap for a near-boundary (fat-
    // finger) tap and expensive for a dead-center (deliberate) tap. That is why spatial's realistic
    // value here is an over-correction brake, not a fix-rate booster — which this gate measures.
    private fun decideWithTaps(
        autocorrect: Autocorrect,
        predictor: LlamaPredictor,
        context: String,
        typed: String,
        isLearned: (String) -> Boolean,
        taps: List<Pair<Double, Double>>,
        spatialOn: Boolean
    ): String? = ContextRescorer.decideCorrection(
        typed = typed,
        neighbors = autocorrect.neighbors(typed, maxEdits = 2),
        fastPick = autocorrect.correct(typed, isLearned),
        scoreAll = { cands ->
            val base = predictor.scoreCorrectionCandidates(context, cands)
            if (spatialOn) DoubleArray(cands.size) { base[it] + spatialLL(cands[it], taps) } else base
        },
        isLearned = isLearned,
        editCost = { cand -> EditCost.cost(typed, cand) }
    )

    @Test
    fun spatialPriorGate_reportsBeforeAfterFixAndOverCorrection() {
        val model = modelFile()
        assumeTrue("GGUF not pushed at ${model.absolutePath}", model.exists())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val autocorrect = Autocorrect(ctx)
        val predictor = LlamaPredictor(model.absolutePath, libDir())
        val learnedSet = setOf("tomas", "priya", "reyes", "nguyen")
        val isLearned: (String) -> Boolean = { learnedSet.contains(it.lowercase()) }
        try {
            // --- Existing corpus, both ways. Deliberately-typed words: every tap on its own key
            // center. Length-changing fixes (definatly->definitely) get a NEUTRAL spatial term, so
            // spatial can only move the same-length cases. This is the apples-to-apples comparison
            // against the shipped 80% fix / 20% over-correction baseline. ---
            for (spatialOn in listOf(false, true)) {
                var fixed = 0
                for ((context, typed, expected) in TYPOS) {
                    val r = decideWithTaps(autocorrect, predictor, context, typed, isLearned, centerTaps(typed), spatialOn)
                    if (r == expected) fixed++
                }
                var over = 0
                for ((context, typed) in LEAVE_ALONE) {
                    val r = decideWithTaps(autocorrect, predictor, context, typed, isLearned, centerTaps(typed), spatialOn)
                    if (r != null && r != typed) over++
                }
                Log.i(
                    TAG,
                    "SPATIAL ${if (spatialOn) "ON " else "OFF"} | existing corpus  " +
                        "fixRate=$fixed/${TYPOS.size} (${pct(fixed.toDouble() / TYPOS.size)})  " +
                        "overCorrection=$over/${LEAVE_ALONE.size} (${pct(over.toDouble() / LEAVE_ALONE.size)})"
                )
            }

            // --- Fat-finger corpus, both ways. These are the error class spatial nominally targets:
            // one adjacent-key substitution, non-word, with the tap on the typed key but pushed a
            // fraction toward the intended neighbour. Reported at three drift levels so the verdict is
            // not an artifact of one hand-picked offset. OFFSET stays < 0.5: the tap is still nearest
            // the typed key (that is why that key registered) — placing it past 0.5 would be a tap
            // that registered as the INTENDED key, i.e. no typo at all. ---
            for (offset in listOf(0.30, 0.40, 0.49)) {
                for (spatialOn in listOf(false, true)) {
                    var fixed = 0
                    for ((context, typed, expected) in FAT_FINGER) {
                        val subPos = typed.indices.first { typed[it] != expected[it] }
                        val taps = fatFingerTaps(typed, subPos, expected[subPos], offset)
                        val r = decideWithTaps(autocorrect, predictor, context, typed, isLearned, taps, spatialOn)
                        if (r == expected) fixed++
                    }
                    Log.i(
                        TAG,
                        "SPATIAL ${if (spatialOn) "ON " else "OFF"} | fat-finger offset=$offset  " +
                            "fixRate=$fixed/${FAT_FINGER.size} (${pct(fixed.toDouble() / FAT_FINGER.size)})"
                    )
                }
            }

            // Setup invariant only: every fat-finger pair must be a single same-position substitution,
            // or the synthetic taps would be meaningless. The real signal is the logged numbers above.
            for ((_, typed, expected) in FAT_FINGER) {
                assertTrue("fat-finger pair '$typed'/'$expected' must be same length", typed.length == expected.length)
                assertTrue(
                    "fat-finger pair '$typed'/'$expected' must differ in exactly one position",
                    typed.indices.count { typed[it] != expected[it] } == 1
                )
            }
        } finally {
            predictor.close()
        }
    }

    private fun pct(x: Double): String = "%.0f%%".format(x * 100)

    companion object {
        private const val TAG = "HushRegressionHarness"
        private const val GGUF_NAME = "smollm2_135m_instruct_q8_0.gguf"

        // Tripwire: fail if over-correction gets WORSE than the measured baseline. Session-77
        // baseline on the A52s was 7/20 = 35%. Session-78 landed the edit-cost prior (Option 2,
        // ed-2 path only) + the learned-words shield (Option 3, names seeded below) and dropped it to
        // 4/20 = 20% with the fix rate held at 80%. 0.25 = the new rate + small headroom. The 4
        // residual over-corrections (yeeted, async, stderr, stdout) are unlearned jargon the model
        // strongly maps to a close dictionary neighbour — the real-world fix is learning them (the
        // shield), not the edit-cost brake. ponytail: hard ceiling, lower it as the number improves.
        private const val MAX_OVER_CORRECTION = 0.25

        // --- Session 81 spatial-prior simulation (test-only). ---

        // Touch jitter as a fraction of key width. STATIC — the same bell curve ships for everyone,
        // never learned per user (the Session-76 trap). 0.4 ~ typical thumb-typing spread.
        private const val SPATIAL_SIGMA = 0.4

        // Hardcoded QWERTY key centres in key-width units (1 key = 1.0). Rows are staggered the
        // usual way; absolute origin is irrelevant — only distances matter. Lazy by design: an
        // offline simulation does not need the real on-screen key bounds (Session 80 note).
        private val KEY_CENTERS: Map<Char, Pair<Double, Double>> = buildMap {
            listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.25, "zxcvbnm" to 0.75)
                .forEachIndexed { r, (row, xOff) ->
                    row.forEachIndexed { i, ch -> put(ch, (i + xOff) to r.toDouble()) }
                }
        }

        private fun center(c: Char): Pair<Double, Double>? = KEY_CENTERS[c.lowercaseChar()]

        // Spatial log-likelihood of [candidate] given the [taps]. The shared -log(2*pi*sigma^2)
        // constant is dropped: it is equal across all same-length candidates, so it cancels in every
        // incumbent-vs-challenger margin comparison. Higher = better spatial fit. Returns 0.0 (a
        // NEUTRAL term) unless [candidate] aligns 1:1 with the taps — spatial geometry has no opinion
        // on inserted/deleted letters, only on same-length substitutions.
        private fun spatialLL(candidate: String, taps: List<Pair<Double, Double>>): Double {
            if (candidate.length != taps.size) return 0.0
            val twoSigmaSq = 2.0 * SPATIAL_SIGMA * SPATIAL_SIGMA
            var sum = 0.0
            for (i in candidate.indices) {
                val ctr = center(candidate[i]) ?: continue
                val dx = taps[i].first - ctr.first
                val dy = taps[i].second - ctr.second
                sum += -(dx * dx + dy * dy) / twoSigmaSq
            }
            return sum
        }

        // A deliberately-typed word: every tap dead-centre on its own key.
        private fun centerTaps(typed: String): List<Pair<Double, Double>> =
            typed.map { center(it) ?: (0.0 to 0.0) }

        // A fat-finger word: every tap on its key centre EXCEPT [subPos], pushed [offset] of the way
        // toward the intended neighbour [intendedChar]. offset < 0.5 keeps the tap nearest the typed
        // key (so that key still registers), modelling a finger that drifted but barely landed wrong.
        private fun fatFingerTaps(
            typed: String,
            subPos: Int,
            intendedChar: Char,
            offset: Double
        ): List<Pair<Double, Double>> = typed.mapIndexed { i, c ->
            val k = center(c) ?: (0.0 to 0.0)
            if (i != subPos) k else {
                val l = center(intendedChar) ?: k
                (k.first + offset * (l.first - k.first)) to (k.second + offset * (l.second - k.second))
            }
        }

        // Fat-finger corpus: (context, typed, intendedFix). Each typed word is a NON-word that differs
        // from its fix by exactly one ADJACENT-key substitution — the error class a spatial prior is
        // for. Hand-verified adjacent on the QWERTY rows above. All synthetic (SECURITY rule 3/4).
        private val FAT_FINGER = listOf(
            Triple("I turned on the", "kight", "light"),   // l->k
            Triple("I drank some", "qater", "water"),       // w->q
            Triple("She picked up the", "ohone", "phone"),  // p->o
            Triple("Put it on the", "rable", "table"),      // t->r
            Triple("They live in a", "gouse", "house"),     // h->g
            Triple("Listen to the", "musuc", "music"),      // i->u
            Triple("I have some", "moneu", "money"),        // y->u
            Triple("Please open the", "doot", "door")       // r->t
        )

        // Genuine non-word typos that SHOULD be corrected, with the intended fix. (context, typed,
        // expectedFix). Most have an edit-distance-1 fast pick (the path that fires most reliably on
        // this small model); "tommorow" is the known ed-2 case kept for visibility (see Session 75).
        private val TYPOS = listOf(
            Triple("I will most", "definatly", "definitely"),
            Triple("I need to", "recieve", "receive"),
            Triple("Keep them", "seperate", "separate"),
            Triple("It only", "occured", "occurred"),
            Triple("I do", "beleive", "believe"),
            Triple("What is your", "adress", "address"),
            Triple("That was a silly", "mistke", "mistake"),
            Triple("He is my", "freind", "friend"),
            Triple("I go there", "everyday", "everyday"),  // sanity: real word, must stay
            Triple("See you", "tommorow", "tomorrow")
        )

        // Valid words that must NOT be changed — the set that catches over-correction. Four
        // categories: rare-but-valid words, names, slang, and code terms. (context, typed).
        private val LEAVE_ALONE = listOf(
            // Rare-but-valid words.
            Pair("The song had a steady", "cadence"),
            Pair("She needed a brief", "respite"),
            Pair("He valued", "brevity"),
            Pair("It was a", "lucid"),
            Pair("They gave", "tacit"),
            // Names.
            Pair("Hi my name is", "tomas"),
            Pair("I spoke with", "reyes"),
            Pair("My doctor is", "nguyen"),
            Pair("This is my friend", "priya"),
            // Slang.
            Pair("That movie was", "lowkey"),
            Pair("He just", "yeeted"),
            Pair("I watched a", "mukbang"),
            Pair("He is a total", "rizzler"),
            // Code terms.
            Pair("Make the call", "async"),
            Pair("Check the", "stderr"),
            Pair("Match it with a", "regex"),
            Pair("Deploy with", "kubectl"),
            Pair("It returned a", "nullptr"),
            Pair("First we", "import"),  // common code word that is also a real English word
            Pair("Print to", "stdout")
        )
    }
}
