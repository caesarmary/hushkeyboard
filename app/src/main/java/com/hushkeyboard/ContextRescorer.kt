package com.hushkeyboard

// ContextRescorer — Phase 5 slice 1, the "transformer ranks" decision core. Pure Kotlin, no
// Android imports, and crucially NO native call: the per-candidate context score is INJECTED as
// a `score` lambda. That lambda is where the native nativeScoreCandidates result will plug in
// later; here it is supplied directly so the decision logic is fully unit-testable on the JVM
// with no model and no device (DEFINITION_OF_RIGHT Gate 8, Option 1).
//
// Slice-1 scope: rescore ONLY non-word typos the fast corrector was already going to change.
// Given the fast pick and the candidate corrections, decide conservatively whether sentence
// context justifies overriding the fast pick. Real-word errors, confusions, and completion are
// slice 2/3 and are NOT handled here.
object ContextRescorer {

    // --- Tunable constants. These are TARGET starting guesses, NOT measured values. They must
    // be tuned/measured on-device (Samsung A52s) before slice 1 is called done; see the design
    // doc section 8 "Constants are all TARGETS." ---

    // Max candidates scored per commit (hard cap on native work + latency).
    // TARGET — to be tuned/measured on-device (Samsung A52s).
    const val DEFAULT_MAX_CANDIDATES = 8

    // The model may override the fast pick ONLY if the best alternative's (length-normalized)
    // log-prob beats the fast pick's score by at least this many nats. Conservative on purpose:
    // a small margin would let the model "correct" picks that were already fine.
    // TARGET — to be tuned/measured on-device (Samsung A52s).
    const val DEFAULT_OVERRIDE_MARGIN_NATS = 1.5

    // Slice 2 (real-word homophone override). STRICTER than the non-word bar above: slice 2 may
    // change a word the user spelled CORRECTLY, so the model must be more confident before we even
    // OFFER an alternative. 2.0 nats was chosen from the Session 72 on-device probe (13/13 homophones
    // ranked correctly; median winning margin 3.55 nats; the only two genuinely-ambiguous cases
    // scored 0.21 and 0.56 — both well below 2.0, so they are correctly left alone). Tunable.
    const val DEFAULT_REALWORD_OVERRIDE_MARGIN_NATS = 2.0

    // Decide the winning correction for a non-word typo.
    //
    // [fastPick]   — today's frequency-ranked correction (Autocorrect.correct result, non-null
    //                for the slice-1 path). This is the conservative incumbent.
    // [candidates] — the candidate corrections to consider (from CandidateGenerator). May or may
    //                not contain [fastPick]; the result is well-defined either way.
    // [score]      — per-candidate context score (higher = better fit). For now injected; later
    //                backed by the model. Called at most once per evaluated candidate.
    // [overrideMarginNats] — required margin to override (defaults to the TARGET constant).
    //
    // Returns [fastPick] UNLESS some candidate outscores fastPick by at least the margin, in
    // which case it returns that best-scoring candidate. Ties and near-ties keep the fast pick.
    // Empty or single-candidate inputs degrade gracefully to [fastPick].
    fun decide(
        fastPick: String,
        candidates: List<String>,
        score: (String) -> Double,
        overrideMarginNats: Double = DEFAULT_OVERRIDE_MARGIN_NATS
    ): String {
        // The incumbent's own context score. Scored once; reused as the bar to clear.
        val fastScore = score(fastPick)

        var bestChallenger: String? = null
        var bestChallengerScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            if (c == fastPick) continue   // the incumbent never challenges itself
            val s = score(c)
            if (s > bestChallengerScore) {
                bestChallengerScore = s
                bestChallenger = c
            }
        }

        val challenger = bestChallenger ?: return fastPick
        // Override only if the challenger beats the incumbent by AT LEAST the margin. Using >=
        // means a candidate winning by exactly the margin DOES override (boundary is inclusive),
        // which the tests pin down.
        return if (bestChallengerScore - fastScore >= overrideMarginNats) challenger else fastPick
    }

    // Slice-1b orchestration (pure Kotlin, model still injected). Bridges the dictionary candidate
    // source to the BATCH model scorer and the [decide] core, for a committed NON-WORD [typed].
    //
    // The native scorer scores ALL candidates in ONE call (a DoubleArray), so this takes a batch
    // [scoreAll] rather than the per-candidate lambda [decide] uses; it pre-scores once, then hands
    // [decide] a map-backed per-candidate view. Injecting [scoreAll] keeps this fully JVM-testable
    // with no model and no device (Gate 8 Option 1), exactly like [decide].
    //
    // [typed]      — the verbatim non-word typo being committed.
    // [neighbors]  — the dictionary's ed<=2 neighbors of [typed] in frequency-rank order
    //                (Autocorrect.neighbors(typed, maxEdits=2)).
    // [fastPick]   — today's Autocorrect.correct(typed) result. NULL when the fast corrector found
    //                no edit-distance-1 fix (e.g. "tommorow"); the TYPED WORD ITSELF then stands in as
    //                the conservative incumbent and is scored alongside the neighbors, so a non-word
    //                is replaced ONLY when a neighbor beats its own context score by the margin
    //                (protects plausible names/slang from being clobbered). Correcting a non-word this
    //                way cannot corrupt correctly-typed text (the slice-1 safety invariant).
    // [scoreAll]   — batch context-scorer: given the final candidate list, returns one
    //                length-normalized log-prob per candidate, positionally aligned.
    // [isLearned]  — Session-78 learned-words shield: returns true when [typed] is a word the user
    //                taught the keyboard (or any "real word" check the caller supplies). A learned
    //                word is never auto-corrected — return the fast pick untouched (null on the ed-2
    //                path => leave [typed] alone). Defaults to {false}, so existing callers/tests are
    //                unchanged. Fixes the Session-75 bug where Autocorrect.isKnownWord consulted only
    //                the static dictionary, clobbering names/jargon the user had taught.
    // [editCost]   — Session-78 typo-likelihood prior: per-candidate cost (nats) of the slip that
    //                would turn [typed] into that candidate. It is SUBTRACTED from each candidate's
    //                model score before the margin comparison, so a far-fetched correction (an
    //                arbitrary far-key substitution) must beat the incumbent by margin + its own
    //                implausibility. APPLIED ONLY on the ed-2 path (fastPick == null) — see the body
    //                for why. Defaults to {0.0} (no prior), so existing callers/tests are unchanged;
    //                the service/harness pass { c -> EditCost.cost(typed, c) }.
    //
    // Returns the word to commit, or NULL when there is nothing to commit (no candidates and no
    // fast pick) — caller then leaves [typed] untouched.
    fun decideCorrection(
        typed: String,
        neighbors: List<String>,
        fastPick: String?,
        scoreAll: (List<String>) -> DoubleArray,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        overrideMarginNats: Double = DEFAULT_OVERRIDE_MARGIN_NATS,
        isLearned: (String) -> Boolean = { false },
        editCost: (String) -> Double = { 0.0 }
    ): String? {
        // Shield: a word the user taught us is a real word — never auto-correct it. On the ed-1 path
        // this means "keep the fast pick" would be wrong, so we drop the correction entirely and
        // leave the typed word; fastPick is the incumbent only when typed is NOT learned.
        if (isLearned(typed)) return null
        val generated = CandidateGenerator.forCorrection(typed, neighbors, maxCandidates)
        // No neighbors -> nothing to correct to; leave [typed] untouched (returns null on the ed-2
        // path, where fastPick is also null).
        if (generated.isEmpty()) return fastPick
        // Incumbent = the conservative pick to beat.
        //  * fastPick non-null (ed-1 path): the fast correction is already on screen; it is the
        //    incumbent, and the refine may only UPGRADE it to a better-scoring neighbor.
        //  * fastPick null (ed-2 path): the TYPED WORD ITSELF is the incumbent, scored by the model
        //    alongside the neighbors. A neighbor replaces it only when it beats the typed word's own
        //    context score by the margin. Without this, the ed-2 path would replace EVERY long
        //    non-word that merely has a dictionary neighbor — clobbering plausible names/slang.
        val incumbent = fastPick ?: typed
        // The cap could drop a low-frequency fastPick out of [generated], and [typed] is never in it
        // (CandidateGenerator excludes it); ensure the incumbent is always in the scored set so its
        // bar-to-clear is a real model score, not a sentinel.
        val candidates = if (generated.contains(incumbent)) generated else buildList {
            add(incumbent)
            addAll(generated)
        }
        val scores = scoreAll(candidates)
        require(scores.size == candidates.size) {
            "scoreAll returned ${scores.size} scores for ${candidates.size} candidates"
        }
        // Effective score = model context score MINUS the typo-implausibility of turning [typed]
        // into this candidate. The incumbent (typed itself on the ed-2 path) has edit cost 0, so a
        // challenger must beat it by margin + its own cost — the brake on far-fetched corrections.
        //
        // The prior applies ONLY on the ed-2 path (fastPick == null), where the incumbent is the
        // TYPED word and the real question is "does this even look like a typo?" — the exact gap
        // Session 75 identified. On the ed-1 path a confident fast pick already exists; biasing the
        // model's choice AMONG corrections by edit distance there is the wrong signal, because the
        // right correction can be the farther one (definatly -> definitely, distance 2, beats the
        // distance-1 transposition "defiantly"). So we let the model choose freely once a fast pick
        // exists, and reserve the edit-cost brake for the no-fast-pick case.
        val applyEditCost = fastPick == null
        val byWord = HashMap<String, Double>(candidates.size * 2)
        candidates.forEachIndexed { i, w ->
            byWord[w] = scores[i] - if (applyEditCost) editCost(w) else 0.0
        }
        return decide(
            fastPick = incumbent,
            candidates = candidates,
            score = { byWord[it] ?: Double.NEGATIVE_INFINITY },
            overrideMarginNats = overrideMarginNats
        )
    }

    // Slice-2 decision: for a correctly-spelled real word [typed] that belongs to a confusion family
    // [confusionSet] (from ConfusionSets.candidatesFor — always containing [typed], size >= 2),
    // return the context-preferred alternative to OFFER, or null to leave the typed word alone.
    //
    // Reuses [decide]: the typed word is the incumbent, the family members are the candidates, and
    // the (larger) real-word margin is the bar. An alternative is returned only when it beats the
    // typed word by >= the margin; otherwise null (stay silent — never nag on an ambiguous context).
    // Unlike [decideCorrection] this NEVER auto-applies: the caller offers the result in the strip.
    //
    // [typed]        — the committed real word, lowercase (the input-buffer form).
    // [confusionSet] — [typed]'s confusion family (must contain [typed]); typically
    //                  ConfusionSets.candidatesFor(typed).
    // [scoreAll]     — batch context-scorer, positionally aligned to [confusionSet] (same contract
    //                  as [decideCorrection]'s scoreAll).
    //
    // Returns the alternative member to offer, or null when none clears the margin or input is
    // degenerate (set smaller than 2, or [typed] absent from it).
    fun decideRealWordOffer(
        typed: String,
        confusionSet: List<String>,
        scoreAll: (List<String>) -> DoubleArray,
        overrideMarginNats: Double = DEFAULT_REALWORD_OVERRIDE_MARGIN_NATS
    ): String? {
        if (confusionSet.size < 2 || typed !in confusionSet) return null
        val scores = scoreAll(confusionSet)
        require(scores.size == confusionSet.size) {
            "scoreAll returned ${scores.size} scores for ${confusionSet.size} candidates"
        }
        val byWord = HashMap<String, Double>(confusionSet.size * 2)
        confusionSet.forEachIndexed { i, w -> byWord[w] = scores[i] }
        val winner = decide(
            fastPick = typed,
            candidates = confusionSet,
            score = { byWord[it] ?: Double.NEGATIVE_INFINITY },
            overrideMarginNats = overrideMarginNats
        )
        return winner.takeIf { it != typed }
    }
}
