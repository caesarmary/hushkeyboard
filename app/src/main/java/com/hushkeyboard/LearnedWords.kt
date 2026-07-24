package com.hushkeyboard

/**
 * Phase 4 learned-words dictionary: the PURE in-memory model (word → frequency
 * count) and the rules for what may be learned. No Android-framework imports
 * (Gate 8 Option 1) — fully JUnit-tested.
 *
 * This holds only the data and the capacity logic. It does NOT decide whether the
 * current field is safe to learn from — that field-level decision lives in
 * [LearnedWordsPolicy] (the SECURITY.md rule 4 password exclusion). It does NOT
 * read or write disk — that is [LearnedWordsStore]. Keeping those concerns apart
 * is what lets this class be a plain, device-free unit.
 *
 * Capacity: the dictionary is bounded at [maxWords]. When a brand-new word would
 * push it over the limit, the lowest-count entry is evicted first (least-used
 * forgotten first). This keeps the on-disk file small and bounded.
 */
class LearnedWords(private val maxWords: Int = DEFAULT_MAX_WORDS) {

    // Insertion-ordered so serialize() output is deterministic (eases testing);
    // order has no semantic meaning.
    private val counts = LinkedHashMap<String, Int>()

    val size: Int get() = counts.size

    /**
     * Learn one occurrence of [rawWord]. The word is [normalize]d and checked with
     * [isLearnable]; unlearnable input (too short/long, contains non-letter chars,
     * whitespace) is ignored and returns false. A known word's count is
     * incremented; a new word is inserted (evicting the least-used entry first if
     * the dictionary is full). Returns true if the dictionary changed.
     */
    fun learn(rawWord: String): Boolean {
        val word = normalize(rawWord)
        if (!isLearnable(word)) return false
        val existing = counts[word]
        if (existing != null) {
            counts[word] = existing + 1
            return true
        }
        if (counts.size >= maxWords) evictLeastUsed()
        counts[word] = 1
        return true
    }

    /** Current stored count for [rawWord] (0 if not learned). */
    fun count(rawWord: String): Int = counts[normalize(rawWord)] ?: 0

    /** A copy of the current contents, for serialization or future ranking use. */
    fun snapshot(): Map<String, Int> = LinkedHashMap(counts)

    /**
     * Replace all contents with [entries] (used when loading a decrypted file).
     * Entries beyond [maxWords] are dropped, keeping the highest counts. Invalid
     * words (should not occur in a file we wrote) are skipped via [isLearnable].
     */
    fun replaceAll(entries: Map<String, Int>) {
        counts.clear()
        val accepted = entries.asSequence()
            .filter { isLearnable(normalize(it.key)) && it.value > 0 }
            .sortedByDescending { it.value }
            .take(maxWords)
        for ((word, count) in accepted) counts[normalize(word)] = count
    }

    /** Forget everything (the one-action user clear delegates here). */
    fun clear() = counts.clear()

    private fun evictLeastUsed() {
        var victim: String? = null
        var lowest = Int.MAX_VALUE
        for ((word, count) in counts) {
            if (count < lowest) {
                lowest = count
                victim = word
            }
        }
        victim?.let { counts.remove(it) }
    }

    companion object {
        const val DEFAULT_MAX_WORDS = 2000
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD_LENGTH = 48

        /**
         * Shortest typed prefix that may trigger a learned-word completion. Below
         * this, a one-letter prefix would match too much to be useful, so the
         * suggestion strip shows nothing from the learned dictionary yet.
         */
        const val MIN_COMPLETION_PREFIX_LENGTH = 2

        /**
         * Phase 4 Session 36 (consumption): the PURE prefix-completion lookup that
         * drives the learned-word suggestion. Given a frequency map [counts] (a
         * [snapshot] of a dictionary) and a typed [rawPrefix], return the learned
         * words that START with that prefix, most-frequent first, capped at [limit].
         *
         * Operates on a plain map (not a live [LearnedWords]) so the store can run
         * it against a lock-free snapshot off the hot path, and so it is trivially
         * JUnit-tested (Gate 8 Option 1). The neural model is untouched: this is a
         * dictionary lookup, not a change to decoding.
         *
         * Rules: the prefix is [normalize]d; a prefix shorter than
         * [MIN_COMPLETION_PREFIX_LENGTH] yields nothing; a candidate must be
         * strictly LONGER than the prefix (so the word the user already finished
         * typing is never offered back to them). Ties in count keep map order
         * ([sortedByDescending] is stable), which is insertion order for the
         * [LinkedHashMap] snapshots we produce — so results are deterministic.
         */
        fun completionsFor(counts: Map<String, Int>, rawPrefix: String, limit: Int = 3): List<String> {
            if (limit <= 0) return emptyList()
            val prefix = normalize(rawPrefix)
            if (prefix.length < MIN_COMPLETION_PREFIX_LENGTH) return emptyList()
            return counts.asSequence()
                .filter { it.key.length > prefix.length && it.key.startsWith(prefix) }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(limit)
                .toList()
        }

        /** Lowercase + trim. Learning is case-insensitive; the keyboard handles display casing. */
        fun normalize(rawWord: String): String = rawWord.trim().lowercase()

        /**
         * Whether a (already-[normalize]d) word may be stored. A learnable word is
         * within the length bounds and made only of letters and the apostrophe
         * (e.g. "don't"). This guarantees no tab/newline can reach the on-disk line
         * format, and excludes numbers, symbols, and whitespace runs. At least one
         * letter is required so a lone apostrophe is rejected.
         */
        fun isLearnable(word: String): Boolean {
            if (word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) return false
            var hasLetter = false
            for (ch in word) {
                when {
                    ch.isLetter() -> hasLetter = true
                    ch == '\'' -> {} // apostrophe allowed inside words
                    else -> return false
                }
            }
            return hasLetter
        }
    }
}
