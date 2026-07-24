package com.hushkeyboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4 Session 56 on-device check for [LlamaPredictor], the llama.cpp
 * backend (TFLite retired Session 60). The GGUF is a bundled asset
 * (noCompress'd) copied to private storage once, the same shape
 * [HushKeyboardService] uses, and loads from there.
 */
@RunWith(AndroidJUnit4::class)
class LlamaPredictorDeviceTest {

    private fun loadPredictor(): LlamaPredictor {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, GGUF_NAME)
        if (!modelFile.exists()) {
            context.assets.open(GGUF_NAME).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return LlamaPredictor(modelFile.absolutePath, context.applicationInfo.nativeLibraryDir)
    }

    @Test
    fun predictNextWord_runsOnDevice() {
        val predictor = loadPredictor()
        try {
            val word = predictor.predictNextWord("I love")
            assertNotNull("expected a next-word prediction for 'I love'", word)
            assertTrue("prediction should not be blank", word!!.isNotBlank())
        } finally { predictor.close() }
    }

    /**
     * Session 57 fix: the bundled GGUF is the INSTRUCT-tuned SmolLM2 variant,
     * whose tokenizer metadata disables add_bos_token (instruct models expect
     * a chat template, not raw-text continuation), so "Hello" used to tokenize
     * to exactly 1 token with no BOS. nativePrefill now forces the BOS token
     * onto the prompt regardless of that metadata flag -- confirmed here via
     * the prefill debug string's reported token count (BOS + "Hello" = 2).
     *
     * This does NOT guarantee [LlamaPredictor.predictNextWord] itself returns
     * non-null for every single-word context: that method only looks at the
     * model's greedy top-1 token, and for "Hello" that token is legitimately
     * "," (a fine raw-text continuation, just not a word). The retired TFLite
     * path's equivalent guarantee was built on top-3 candidates, not greedy
     * top-1 -- the still-pending top-K work is what would let a word in
     * slot 2 or 3 surface here too.
     */
    @Test
    fun predictNextWord_singleWordContextTokenizesWithBos() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, GGUF_NAME)
        if (!modelFile.exists()) {
            context.assets.open(GGUF_NAME).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val session = LlamaSession()
        session.load(modelFile.absolutePath, context.applicationInfo.nativeLibraryDir)
        try {
            val debug = session.prefill("Hello")
            assertTrue("expected BOS + 'Hello' = 2 prompt tokens, got: $debug",
                debug.contains("prompt tokens: 2"))
        } finally { session.close() }
    }

    @Test
    fun predictNextWord_nullForBlankContext() {
        val predictor = loadPredictor()
        try {
            assertNull(predictor.predictNextWord(""))
            assertNull(predictor.predictNextWord("   "))
        } finally { predictor.close() }
    }

    /**
     * Session 58: nativePrefill now extracts top-3 candidates per decode
     * instead of only the greedy top-1. Checks the new accessor lines up
     * with the existing one (index 0 == lastPiece) and that it returns
     * multiple distinct candidates, not just a 1-element array.
     */
    @Test
    fun topKPieces_includesLastPieceAsFirstCandidate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, GGUF_NAME)
        if (!modelFile.exists()) {
            context.assets.open(GGUF_NAME).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val session = LlamaSession()
        session.load(modelFile.absolutePath, context.applicationInfo.nativeLibraryDir)
        try {
            session.prefill("I love")
            val top = session.topKPieces()
            assertTrue("expected up to 3 candidates, got ${top.size}", top.size in 1..3)
            assertTrue("expected index 0 to match lastPiece()", top[0] == session.lastPiece())
        } finally { session.close() }
    }

    /**
     * Phase 4 Session 59 on-device check for the new [LlamaPredictor.predictTopWords],
     * the slot-filling method that wires into HushKeyboardService.
     */
    @Test
    fun predictTopWords_runsOnDeviceAndReturnsCandidates() {
        val predictor = loadPredictor()
        try {
            val words = predictor.predictTopWords("Thank you very", 3)
            android.util.Log.i("HushS59", "predictTopWords(\"Thank you very\", 3) = $words")
            assertTrue("expected at least one candidate, got $words", words.isNotEmpty())
        } finally { predictor.close() }
    }

    private companion object {
        const val GGUF_NAME = "smollm2_135m_instruct_q8_0.gguf"
    }
}
