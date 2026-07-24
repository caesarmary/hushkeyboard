package com.hushkeyboard

/**
 * Phase 4 next-word prediction: the pure, JVM-testable logic that surrounds the
 * model call. No Android-framework imports (Gate 8 Option 1) — so it is
 * fully exercised by plain JUnit tests.
 *
 * It holds two things:
 *   1. The SECURITY-CRITICAL gating decision — whether prediction may run at all
 *      for a given field (password-field exclusion). This is the testable-layer
 *      decision Gate 8 reserved; see [isEligible] and SECURITY.md rule 4.
 *   2. The text shaping around the model: turning raw text-before-cursor into the
 *      model's context string ([buildContext]); choosing a next-word suggestion
 *      from the model's top-k decoded candidates ([pickNextWord] /
 *      [pickNextWordIndex]); deciding when a greedy multi-token word is finished
 *      ([isWordContinuation]); and sentence-start capitalization
 *      ([capitalizeForSentenceStart]).
 *
 * The framework-coupled work (reading the InputConnection, the worker thread,
 * updating the suggestion strip) lives in HushKeyboardService and is a thin
 * shell over this class.
 */
object PredictionContext {

    private val SENTENCE_ENDERS = charArrayOf('.', '!', '?')

    /**
     * Whether the next-word predictor may run for this field.
     *
     * SECURITY-CRITICAL (SECURITY.md rule 4): prediction reads recent typed text
     * and runs it through the model, so it must NEVER run in a password field.
     * Gated on [SensitiveFieldChecker.isPasswordField]. It is also off when the
     * user has disabled suggestions entirely.
     *
     * NO_SUGGESTIONS fields (e.g. Google Search) are intentionally allowed —
     * reading a search query carries no privacy risk, the same stance taken for
     * long-press word deletion (see SensitiveFieldChecker).
     */
    fun isEligible(inputType: Int, suggestionsEnabled: Boolean): Boolean =
        suggestionsEnabled && !SensitiveFieldChecker.isPasswordField(inputType)

    /**
     * Shape the raw text-before-cursor into the model's context string for
     * NEXT-word prediction. We want the word that FOLLOWS the context, so a
     * trailing space (or newline) is stripped: the model then emits the next
     * word as a leading-space token — e.g. "Thank you very " -> context
     * "Thank you very" -> the model predicts " much". Returns null when there is
     * no usable context (null / empty / whitespace-only).
     */
    fun buildContext(textBefore: CharSequence?): String? {
        if (textBefore.isNullOrEmpty()) return null
        val trimmed = textBefore.toString().trimEnd()
        return trimmed.ifEmpty { null }
    }

    /**
     * Whether a decoded candidate string STARTS a new word, the predicate behind
     * both [pickNextWord] and [pickNextWordIndex]. It must begin with a space
     * (the byte-level leading-space marker decodes to a real ' ') and contain at
     * least one letter or digit once trimmed (so pure punctuation and empty
     * fragments are skipped).
     */
    private fun isNextWordCandidate(decoded: String): Boolean {
        if (decoded.isEmpty() || decoded[0] != ' ') return false
        val word = decoded.trim()
        return word.isNotEmpty() && word.any { it.isLetterOrDigit() }
    }

    /**
     * Choose the next-word suggestion from the model's top-k decoded candidate
     * strings (rank order, highest probability first): the first candidate that
     * STARTS a new word ([isNextWordCandidate]), trimmed for display, or null if
     * none qualifies.
     *
     * This is the FIRST subword of the next word. Multi-token completion of that
     * word (decoding further pieces) is driven by [LlamaPredictor] using
     * [pickNextWordIndex] (to recover the chosen token's id) and
     * [isWordContinuation] (to know when to stop).
     */
    fun pickNextWord(candidates: List<String>): String? =
        candidates.firstOrNull { isNextWordCandidate(it) }?.trim()

    /**
     * Index of the first next-word candidate in [candidates], or -1 if none
     * qualifies. Lets [LlamaPredictor] recover the matching token id (the
     * candidate at the same index in the top-k id list) so it can keep decoding
     * that word greedily.
     */
    fun pickNextWordIndex(candidates: List<String>): Int =
        candidates.indexOfFirst { isNextWordCandidate(it) }

    /**
     * Indices of ALL candidates that START a new word ([isNextWordCandidate]), in
     * rank order. The plural sibling of [pickNextWordIndex], used by
     * [LlamaPredictor.predictTopWords] to complete the top few distinct
     * next-word suggestions that fill the whole strip (Session 31).
     */
    fun nextWordIndices(candidates: List<String>): List<Int> =
        candidates.indices.filter { isNextWordCandidate(candidates[it]) }

    /**
     * Whether a decoded candidate CONTINUES the current word (multi-token greedy
     * completion, Session 29). A continuation has NO leading space (a leading
     * space would mark a NEW word) and contains at least one letter or digit (so
     * we stop at punctuation, quotes, or end-of-text). Examples: "ing", "able",
     * "n't" continue; " the" (new word), "." and "," (end) do not.
     *
     * Honest v1 limit: a mid-word piece that is pure punctuation (e.g. the hyphen
     * in "well-being") reads as non-continuation, so such a word completes only
     * up to the hyphen. Rare for keyboard next-word use; same out-of-scope class
     * as the single-token note this replaces.
     */
    fun isWordContinuation(decoded: String): Boolean {
        if (decoded.isEmpty() || decoded[0] == ' ') return false
        return decoded.any { it.isLetterOrDigit() }
    }

    /**
     * Capitalize [word] when [context] indicates the start of a sentence: the
     * context is empty (or whitespace-only), or its last non-space character is
     * sentence-ending punctuation ('.', '!', '?'). Otherwise [word] is returned
     * unchanged. Pure; Session 29.
     *
     * Honest limit: only '.', '!', '?' count as sentence enders, and trailing
     * quotes/brackets after them (e.g. '?"') are not unwrapped — the following
     * word then is not capitalized. Acceptable v1 behavior; "I" and proper nouns
     * are not special-cased (that is lexical, out of scope).
     */
    fun capitalizeForSentenceStart(word: String, context: String): String {
        if (word.isEmpty()) return word
        val trimmed = context.trimEnd()
        val atSentenceStart = trimmed.isEmpty() || trimmed.last() in SENTENCE_ENDERS
        return if (atSentenceStart) word.replaceFirstChar { it.uppercaseChar() } else word
    }
}
