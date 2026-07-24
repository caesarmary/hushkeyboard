package com.hushkeyboard

/**
 * Phase 4 Session 56: live llama.cpp-backed next-word prediction (TFLite
 * retired Session 60 -- this is the only backend now). Gate 8 Option 1 --
 * no Android-framework imports, so [HushKeyboardService] only needs to load
 * the model and supply text; this class never touches an InputConnection
 * and logs nothing.
 *
 * Greedy multi-token word completion uses the stop condition
 * [PredictionContext.isWordContinuation] and the caps [MAX_GREEDY_STEPS] /
 * [TIME_BUDGET_MS].
 */
class LlamaPredictor(modelPath: String, libDir: String) {

    private val session = LlamaSession()

    init {
        session.load(modelPath, libDir)
    }

    /**
     * Predict the word following [contextText]. No BOS-prepend hack is
     * needed for short context: llama_tokenize is called with add_bos=true
     * inside the native layer regardless of input length, so even a
     * one-word context already has the >=1 prompt token llama.cpp needs to
     * prefill.
     *
     * Returns null when [contextText] is blank (nothing to predict from) or
     * the first decoded piece is not a new word (e.g. punctuation).
     */
    fun predictNextWord(contextText: String): String? {
        if (contextText.isBlank()) return null

        session.prefill(contextText)
        val first = session.lastPiece()
        val word0 = PredictionContext.pickNextWord(listOf(first)) ?: return null

        var word = word0
        var steps = 0
        val start = System.currentTimeMillis()
        while (steps < MAX_GREEDY_STEPS && System.currentTimeMillis() - start < TIME_BUDGET_MS) {
            session.decodeNext()
            val piece = session.lastPiece()
            if (!PredictionContext.isWordContinuation(piece)) break
            word += piece
            steps++
        }

        return PredictionContext.capitalizeForSentenceStart(word, contextText)
    }

    /**
     * Phase 4 Session 59: top-[n] next-word suggestions to fill the whole
     * suggestion strip.
     *
     * [LlamaSession]'s KV-cache is one mutable context, not branchable, so
     * only the greedy top-1 candidate ([LlamaSession.topKPieces] index 0 --
     * the same token [lastPiece] tracks) can be multi-token completed; that
     * is just [predictNextWord]'s own decode loop. Slots 2/3 are shown as
     * their single decoded subword, uncompleted -- true multi-candidate
     * completion would need KV-cache snapshot/restore in the JNI layer.
     * Session 60 decision: not worth building for v1 -- each extra decode
     * step costs ~17ms warm (measured on the A52s, Session 71; the older
     * ~300ms figure was the retired TFLite path), so completing 2 more
     * candidates adds two more decode loops of latency for a cosmetic gain.
     * Documented limitation; revisit if a faster model or
     * quantization frees up latency headroom. The point of top-3 (Session
     * 58) is giving the user real alternatives when the greedy top-1 alone
     * is the unreliable pick for short context (Session 57), not completing
     * all three.
     *
     * Returned words are distinct (case-insensitive), in rank order. Empty
     * when [contextText] is blank or no candidate starts a new word.
     */
    fun predictTopWords(contextText: String, n: Int = 3): List<String> {
        if (contextText.isBlank() || n <= 0) return emptyList()

        session.prefill(contextText)
        val pieces = session.topKPieces()
        val indices = PredictionContext.nextWordIndices(pieces)

        val out = ArrayList<String>(n)
        val seen = HashSet<String>(n * 2)
        for (index in indices) {
            if (out.size >= n) break
            val word = if (index == 0) completeTopWord(pieces[0]) else pieces[index].trim()
            if (word.isNotEmpty() && seen.add(word.lowercase())) {
                out.add(PredictionContext.capitalizeForSentenceStart(word, contextText))
            }
        }
        return out
    }

    /** Greedily extends the greedy top-1 [firstWord], same loop/caps as [predictNextWord]. */
    private fun completeTopWord(firstWord: String): String {
        var word = firstWord.trim()
        var steps = 0
        val start = System.currentTimeMillis()
        while (steps < MAX_GREEDY_STEPS && System.currentTimeMillis() - start < TIME_BUDGET_MS) {
            session.decodeNext()
            val piece = session.lastPiece()
            if (!PredictionContext.isWordContinuation(piece)) break
            word += piece
            steps++
        }
        return word
    }

    /**
     * Phase 5 Session 71 (slice 1b): context-aware autocorrect rescoring. For a
     * committed non-word typo, score each [candidates] correction by how well it
     * fits the sentence [context] that precedes it. Prefills [context] ONCE (the
     * same prefill path prediction uses — no new field-read site), then issues a
     * single native scoring call for all candidates, returning their
     * length-normalized log-probs positionally aligned.
     *
     * This is the [ContextRescorer.decideCorrection] `scoreAll` injection point.
     * Blank context or no candidates short-circuits to NEGATIVE_INFINITY scores
     * (nothing to rank against), so the rescorer keeps the conservative fast pick.
     */
    fun scoreCorrectionCandidates(context: String, candidates: List<String>): DoubleArray {
        if (context.isBlank() || candidates.isEmpty()) {
            return DoubleArray(candidates.size) { Double.NEGATIVE_INFINITY }
        }
        session.prefill(context)
        return session.scoreCandidates(candidates)
    }

    fun close() = session.close()

    private companion object {
        // At most 2 extra subwords, bounded by wall-clock too.
        const val MAX_GREEDY_STEPS = 2
        const val TIME_BUDGET_MS = 600L
    }
}
