package com.hushkeyboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SESSION 55 on-device check for the realistic per-keystroke shape: load the
 * model once, prefill a prompt, then run N sequential single-token decodes
 * (the loop a live keyboard actually does, one call per keystroke) instead
 * of the one-shot smoke test. The GGUF is not a bundled asset (same
 * deferred-asset pattern as Session 53) — push it once via:
 *   adb push smollm2_135m_instruct_q8_0.gguf \
 *     /sdcard/Android/data/com.hushkeyboard/files/smollm2_135m_instruct_q8_0.gguf
 * If it isn't present, this test is SKIPPED, not failed.
 */
@RunWith(AndroidJUnit4::class)
class LlamaSessionDeviceTest {

    @Test
    fun decodeNext_repeatedCalls_allSucceedUnderLatencyBound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.getExternalFilesDir(null), GGUF_NAME)
        assumeTrue("GGUF not pushed at ${modelFile.absolutePath}", modelFile.exists())

        val session = LlamaSession()
        try {
            session.load(modelFile.absolutePath, context.applicationInfo.nativeLibraryDir)

            val prefillResult = session.prefill(PROMPT)
            Log.i(TAG, "prefill: $prefillResult")
            assertTrue("prefill should report OK, got: $prefillResult", prefillResult.startsWith("OK"))

            repeat(DECODE_STEPS) { step ->
                val stepResult = session.decodeNext()
                Log.i(TAG, "decodeNext[$step]: $stepResult")
                assertFalse("decode step $step failed: $stepResult", stepResult.startsWith("FAIL"))

                val ms = Regex("""decode time: (\d+) ms""").find(stepResult)
                    ?.groupValues?.get(1)?.toLongOrNull()
                assertTrue("decode step $step missing timing in: $stepResult", ms != null)
                assertTrue(
                    "decode step $step took ${ms}ms, expected under ${MAX_DECODE_MS}ms",
                    ms!! < MAX_DECODE_MS
                )
            }
        } finally {
            session.close()
        }
    }

    companion object {
        private const val TAG = "HushLlamaSession"
        private const val GGUF_NAME = "smollm2_135m_instruct_q8_0.gguf"
        private const val PROMPT = "The quick brown fox jumps over the lazy"
        private const val DECODE_STEPS = 20
        private const val MAX_DECODE_MS = 150L
    }
}
