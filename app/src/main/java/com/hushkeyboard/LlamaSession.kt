package com.hushkeyboard

/**
 * Phase 4 Session 55 — stateful counterpart to [LlamaSmokeTest]. That smoke
 * test loads the model, decodes once, and frees everything; a live keyboard
 * instead loads once and does many cheap single-token decodes against the
 * same context as the user types. This class measures that shape: [load]
 * once, [prefill] a starting prompt, then [decodeNext] repeatedly. Same
 * Gate-8 posture as [LlamaSmokeTest] (Option 1 — no Android-framework
 * imports; the caller handles paths and reporting). Reads no user input —
 * the prefill prompt is caller-supplied for measurement purposes only.
 */
class LlamaSession {

    private var handle: Long = 0L

    private external fun nativeLoad(modelPath: String, libDir: String): Long
    private external fun nativePrefill(handle: Long, prompt: String): String
    private external fun nativeDecodeNext(handle: Long): String
    private external fun nativeLastPiece(handle: Long): String
    private external fun nativeTopKPieces(handle: Long): Array<String>
    private external fun nativeScoreCandidates(handle: Long, candidates: Array<String>): DoubleArray
    private external fun nativeFree(handle: Long)

    fun load(modelPath: String, libDir: String) {
        handle = nativeLoad(modelPath, libDir)
        check(handle != 0L) { "LlamaSession: native load failed for $modelPath" }
    }

    fun prefill(prompt: String): String = nativePrefill(handle, prompt)

    fun decodeNext(): String = nativeDecodeNext(handle)

    /**
     * The decoded text of the token [prefill] or [decodeNext] just produced
     * (the model's current top-1 next token), with no debug-string wrapper to
     * parse. Phase 4 Session 56, for production callers like [LlamaPredictor];
     * [prefill]/[decodeNext]'s own string returns stay measurement-only.
     */
    fun lastPiece(): String = nativeLastPiece(handle)

    /**
     * Up to 3 candidate next-token pieces from the decode [prefill] or
     * [decodeNext] just ran, descending by logit (index 0 is [lastPiece]'s
     * token). Phase 4 Session 58, for filling the suggestion strip's 3 slots
     * and for single-word context where the greedy top-1 alone is unreliable.
     */
    fun topKPieces(): List<String> = nativeTopKPieces(handle).toList()

    /**
     * Phase 5 Session 71 (slice 1b): length-normalized conditional log-probs
     * log P(candidate | context) / tokens, one per candidate, positionally
     * aligned. Requires [prefill] to have run already (the context KV-cache it
     * leaves is what each candidate is scored against). Returns ONLY doubles —
     * the native side logs/retains no candidate or context text. Empty input
     * returns an empty array without a native call.
     */
    fun scoreCandidates(candidates: List<String>): DoubleArray =
        if (candidates.isEmpty()) DoubleArray(0)
        else nativeScoreCandidates(handle, candidates.toTypedArray())

    fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("hushllama")
        }
    }
}
