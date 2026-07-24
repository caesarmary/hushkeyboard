package com.hushkeyboard

/**
 * Phase 4 Session 53 — thin JNI wrapper around the pinned llama.cpp build
 * from Session 52. Deliberately free of any Android-framework imports
 * (Gate 8, Option 1): this class only declares the native entry point.
 * Loading the GGUF asset, finding the native library
 * directory, and showing a result all live in the caller (`MainActivity`).
 *
 * The native call is stateless and one-shot: load model, register the CPU
 * backend, tokenize [prompt], decode once, free everything, return a result
 * string. This is a SMOKE TEST, not the live predictor — it does not keep a
 * model handle around and reads no user input.
 */
class LlamaSmokeTest {

    external fun nativeRunSmokeTest(modelPath: String, libDir: String, prompt: String): String

    companion object {
        init {
            System.loadLibrary("hushllama")
        }
    }
}
