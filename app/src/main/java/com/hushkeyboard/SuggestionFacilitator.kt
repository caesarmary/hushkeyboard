package com.hushkeyboard

/**
 * Phase 4 Session 49 — the suggestion-strip COORDINATOR. FUTO calls its analog the
 * LanguageModelFacilitator; ours is deliberately smaller and runtime-agnostic.
 *
 * It merges the three candidate sources hushkeyboard already produces — the static
 * dictionary correction ([Autocorrect.correct]), the learned-word prefix completions
 * ([LearnedWords.completionsFor]), and the neural next-word top-k
 * ([LlamaPredictor.predictTopWords]) — into the three suggestion slots, de-duplicated
 * and sensibly ordered.
 *
 * PURE on purpose (Gate 8 Option 1): it takes only plain strings/lists and returns a
 * plain [Strip]. It does NOT call the engine, hold a KV-cache, read logits, or import
 * anything Android-specific — so it is trivially JUnit-tested and runtime-agnostic.
 *
 * SECURITY: this class never sees the input field. The SECURITY.md rule-4 password gate
 * stays entirely in [HushKeyboardService] — the service only reaches this merge AFTER its
 * password early-return, and only passes neural words for prediction-eligible fields.
 *
 * Out of scope (deferred past the runtime swap): LM-rescoring, latency tuning, and
 * learned-words contextual blending.
 */
object SuggestionFacilitator {

    /**
     * The three slot strings to display, plus [centerCommit]: the raw word the CENTER
     * slot inserts when tapped WHILE TYPING (it replaces the typed word). It is null at a
     * word boundary, where the center slot instead holds a next-word prediction that the
     * service commits with a trailing space. The service applies display casing on top.
     */
    data class Strip(
        val left: String?,
        val center: String?,
        val right: String?,
        val centerCommit: String?,
    )

    /**
     * Merge the sources into the strip.
     *
     * Boundary ([typedWord] empty): the slots predict the NEXT word, filled from the
     * distinct [neuralNextWords] in rank order. No dictionary/learned candidates apply
     * (there is no prefix to complete). [centerCommit] is null.
     *
     * Typing ([typedWord] non-empty): left = the verbatim typed word (so the user can keep
     * it), center = the best completion of the current word ([correction] if any, else the
     * top [learnedCompletions]), right = the neural next-word look-ahead. All three are
     * distinct case-insensitively: the right look-ahead skips any neural word that repeats
     * the typed word or the center candidate, falling through to the next neural word.
     */
    fun merge(
        typedWord: String,
        correction: String?,
        learnedCompletions: List<String>,
        neuralNextWords: List<String>,
    ): Strip {
        if (typedWord.isEmpty()) {
            val distinct = distinctInOrder(neuralNextWords)
            return Strip(
                left = distinct.getOrNull(0),
                center = distinct.getOrNull(1),
                right = distinct.getOrNull(2),
                centerCommit = null,
            )
        }

        val center = correction
            ?: learnedCompletions.firstOrNull { it.isNotEmpty() && !it.equals(typedWord, ignoreCase = true) }
        val taken = listOfNotNull(typedWord, center)
        val right = neuralNextWords.firstOrNull { cand ->
            cand.isNotEmpty() && taken.none { it.equals(cand, ignoreCase = true) }
        }
        return Strip(left = typedWord, center = center, right = right, centerCommit = center)
    }

    /** Non-empty words, first occurrence wins, compared case-insensitively. */
    private fun distinctInOrder(words: List<String>): List<String> {
        val seen = HashSet<String>(words.size)
        val out = ArrayList<String>(words.size)
        for (w in words) if (w.isNotEmpty() && seen.add(w.lowercase())) out.add(w)
        return out
    }
}
