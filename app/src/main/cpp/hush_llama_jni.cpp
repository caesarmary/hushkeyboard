#include <jni.h>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

// Session 53 smoke test. One-shot, stateless: load the model, register the
// CPU backend, tokenize a hardcoded prompt, decode once, return the top-1
// next token and timings. No persistent handle — the live decode-loop
// wiring (LlamaSession/LlamaPredictor) is separate, later work. Reads no
// user input.
extern "C" JNIEXPORT jstring JNICALL
Java_com_hushkeyboard_LlamaSmokeTest_nativeRunSmokeTest(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath, jstring jLibDir, jstring jPrompt) {

    const char *modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    const char *libDirChars = env->GetStringUTFChars(jLibDir, nullptr);
    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string modelPath(modelPathChars);
    const std::string libDir(libDirChars);
    const std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);
    env->ReleaseStringUTFChars(jLibDir, libDirChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    std::string out;

    // Registers the CPU backend from the app's own native-library directory.
    // Mirrors llama.cpp's own examples (e.g. examples/simple/simple.cpp),
    // which call this unconditionally even for CPU-only builds. Timed
    // separately (Session 54) to see whether the directory-scan/dlopen cost
    // is meaningful next to model load.
    auto tBackend0 = std::chrono::steady_clock::now();
    ggml_backend_load_all_from_path(libDir.c_str());
    auto tBackend1 = std::chrono::steady_clock::now();

    if (ggml_backend_dev_count() == 0) {
        out = "FAIL: no ggml backend loaded from " + libDir;
    } else {
        auto t0 = std::chrono::steady_clock::now();

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0; // CPU only

        llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
        if (model == nullptr) {
            out = "FAIL: llama_model_load_from_file returned null for " + modelPath;
        } else {
            auto t1 = std::chrono::steady_clock::now();
            const llama_vocab *vocab = llama_model_get_vocab(model);

            int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                                            nullptr, 0, true, true);
            std::vector<llama_token> tokens(n_prompt);
            llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                            tokens.data(), n_prompt, true, true);

            llama_context_params cparams = llama_context_default_params();
            cparams.n_ctx = n_prompt + 8;
            cparams.n_batch = n_prompt;
            cparams.no_perf = false;

            auto t1b = std::chrono::steady_clock::now();
            llama_context *ctx = llama_init_from_model(model, cparams);
            auto t1c = std::chrono::steady_clock::now();
            if (ctx == nullptr) {
                out = "FAIL: llama_init_from_model returned null";
            } else {
                llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());

                auto t2 = std::chrono::steady_clock::now();
                int decodeRc = llama_decode(ctx, batch);
                auto t3 = std::chrono::steady_clock::now();

                if (decodeRc != 0) {
                    out = "FAIL: llama_decode returned " + std::to_string(decodeRc);
                } else {
                    float *logits = llama_get_logits_ith(ctx, -1);
                    int n_vocab = llama_vocab_n_tokens(vocab);
                    int best = 0;
                    float bestVal = logits[0];
                    for (int i = 1; i < n_vocab; i++) {
                        if (logits[i] > bestVal) {
                            bestVal = logits[i];
                            best = i;
                        }
                    }
                    char piece[128];
                    int n = llama_token_to_piece(vocab, best, piece, sizeof(piece), 0, true);
                    std::string word(piece, n > 0 ? n : 0);

                    auto backendMs = std::chrono::duration_cast<std::chrono::milliseconds>(tBackend1 - tBackend0).count();
                    auto loadMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
                    auto ctxMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1c - t1b).count();
                    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t2).count();

                    out = "OK\nprompt tokens: " + std::to_string(n_prompt) +
                          "\nbackend load (dlopen scan): " + std::to_string(backendMs) + " ms" +
                          "\nmodel load time: " + std::to_string(loadMs) + " ms" +
                          "\ncontext init (KV cache+graph): " + std::to_string(ctxMs) + " ms" +
                          "\ndecode time (prefill+1): " + std::to_string(decodeMs) + " ms" +
                          "\ntop-1 next token: \"" + word + "\" (id " + std::to_string(best) + ")";
                }
                llama_free(ctx);
            }
            llama_model_free(model);
        }
    }

    return env->NewStringUTF(out.c_str());
}

// Session 55. The smoke test above is one-shot: load, decode once, free.
// A live keyboard instead loads once and does many cheap single-token
// decodes against the same context as the user types. LlamaSession is the
// stateful counterpart that measures THAT shape: nativeLoad (model only),
// nativePrefill (tokenize + context + first decode), nativeDecodeNext
// (one token, reusing the context's own KV-cache), nativeFree. Still reads
// no user input — the prefill prompt is caller-supplied for measurement
// purposes, same as the smoke test's hardcoded prompt.
struct LlamaSessionState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_token lastToken = 0;
    int nPast = 0;
    llama_token top3[3] = {0, 0, 0};
    int top3Count = 0;
};

// Session 58: partial top-3 selection, same approach as the TFLite path's
// Predictor.kt::topK (Session 29 finding: a full sort of the ~49k-entry
// vocab is wasted work on this hot path when k is tiny -- a single O(n*3)
// pass that keeps only the running top-3 returns the same ids). Writes the
// ids into outIds[0..2] descending and returns how many were filled.
static int topK3(const float *logits, int n_vocab, llama_token *outIds) {
    float topVal[3] = {-INFINITY, -INFINITY, -INFINITY};
    outIds[0] = outIds[1] = outIds[2] = -1;
    for (int i = 0; i < n_vocab; i++) {
        float x = logits[i];
        if (x <= topVal[2]) continue;
        int pos = 2;
        while (pos > 0 && topVal[pos - 1] < x) {
            topVal[pos] = topVal[pos - 1];
            outIds[pos] = outIds[pos - 1];
            pos--;
        }
        topVal[pos] = x;
        outIds[pos] = i;
    }
    int count = 0;
    while (count < 3 && outIds[count] >= 0) count++;
    return count;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hushkeyboard_LlamaSession_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring jModelPath, jstring jLibDir) {
    const char *modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    const char *libDirChars = env->GetStringUTFChars(jLibDir, nullptr);
    const std::string modelPath(modelPathChars);
    const std::string libDir(libDirChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);
    env->ReleaseStringUTFChars(jLibDir, libDirChars);

    ggml_backend_load_all_from_path(libDir.c_str());
    if (ggml_backend_dev_count() == 0) {
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only
    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        return 0;
    }

    auto *state = new LlamaSessionState();
    state->model = model;
    state->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hushkeyboard_LlamaSession_nativePrefill(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jPrompt) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    if (state == nullptr || state->model == nullptr) {
        return env->NewStringUTF("FAIL: invalid session handle");
    }

    const char *promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    int n_prompt = -llama_tokenize(state->vocab, prompt.c_str(), (int) prompt.size(),
                                    nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(state->vocab, prompt.c_str(), (int) prompt.size(),
                    tokens.data(), n_prompt, true, true);

    // The bundled SmolLM2-instruct GGUF sets tokenizer.ggml.add_bos_token=false,
    // so add_special above is a no-op and short (single-word) prompts decode
    // with no BOS context at all. Force it regardless of that metadata flag.
    llama_token bos = llama_vocab_bos(state->vocab);
    if (tokens.empty() || tokens.front() != bos) {
        tokens.insert(tokens.begin(), bos);
    }
    int n_tokens = (int) tokens.size();

    // Headroom (+40) for the decode-loop calls that follow this prefill.
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_tokens + 40;
    cparams.n_batch = n_tokens;
    cparams.no_perf = false;

    llama_context *ctx = llama_init_from_model(state->model, cparams);
    if (ctx == nullptr) {
        return env->NewStringUTF("FAIL: llama_init_from_model returned null");
    }
    state->ctx = ctx;

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    auto t0 = std::chrono::steady_clock::now();
    int decodeRc = llama_decode(ctx, batch);
    auto t1 = std::chrono::steady_clock::now();
    if (decodeRc != 0) {
        return env->NewStringUTF(("FAIL: llama_decode returned " + std::to_string(decodeRc)).c_str());
    }

    float *logits = llama_get_logits_ith(ctx, -1);
    int n_vocab = llama_vocab_n_tokens(state->vocab);
    state->top3Count = topK3(logits, n_vocab, state->top3);
    int best = state->top3[0];
    state->lastToken = best;
    state->nPast = n_tokens;

    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    char piece[128];
    int n = llama_token_to_piece(state->vocab, best, piece, sizeof(piece), 0, true);
    std::string word(piece, n > 0 ? n : 0);
    std::string out = "OK\nprompt tokens: " + std::to_string(n_tokens) +
                       "\nprefill+decode time: " + std::to_string(decodeMs) + " ms" +
                       "\ntop-1 next token: \"" + word + "\" (id " + std::to_string(best) + ")";
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hushkeyboard_LlamaSession_nativeDecodeNext(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    if (state == nullptr || state->ctx == nullptr) {
        return env->NewStringUTF("FAIL: invalid session handle (call nativePrefill first)");
    }

    llama_token tokenIn = state->lastToken;
    llama_batch batch = llama_batch_get_one(&tokenIn, 1);
    // The KV-cache already holds positions [0, nPast); llama_decode appends
    // this one token at nPast using the context's own internal position
    // tracking — no cache bookkeeping needed on our side, unlike the
    // TFLite path's explicit cache tensors.
    auto t0 = std::chrono::steady_clock::now();
    int decodeRc = llama_decode(state->ctx, batch);
    auto t1 = std::chrono::steady_clock::now();
    if (decodeRc != 0) {
        return env->NewStringUTF(("FAIL: llama_decode returned " + std::to_string(decodeRc)).c_str());
    }

    float *logits = llama_get_logits_ith(state->ctx, -1);
    int n_vocab = llama_vocab_n_tokens(state->vocab);
    state->top3Count = topK3(logits, n_vocab, state->top3);
    int best = state->top3[0];
    state->lastToken = best;
    state->nPast += 1;

    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    char piece[128];
    int n = llama_token_to_piece(state->vocab, best, piece, sizeof(piece), 0, true);
    std::string word(piece, n > 0 ? n : 0);
    std::string out = "OK\ndecode time: " + std::to_string(decodeMs) + " ms" +
                       "\ntop-1 next token: \"" + word + "\" (id " + std::to_string(best) + ")";
    return env->NewStringUTF(out.c_str());
}

// Phase 4 Session 56. Production accessor for the token nativePrefill/
// nativeDecodeNext just produced, as plain decoded text -- no debug-string
// wrapper to parse. Reuses state->lastToken; no extra decode work.
extern "C" JNIEXPORT jstring JNICALL
Java_com_hushkeyboard_LlamaSession_nativeLastPiece(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    if (state == nullptr || state->vocab == nullptr) {
        return env->NewStringUTF("");
    }
    char piece[128];
    int n = llama_token_to_piece(state->vocab, state->lastToken, piece, sizeof(piece), 0, true);
    return env->NewStringUTF(std::string(piece, n > 0 ? n : 0).c_str());
}

// Phase 4 Session 58. Top-3 candidates from the last prefill/decodeNext,
// as decoded text -- the suggestion strip needs 3 slots, and a single
// greedy top-1 token is unreliable for short/single-word context (see
// LlamaPredictorDeviceTest's predictNextWord_singleWordContextTokenizesWithBos
// note). [lastToken]/greedy continuation in nativeDecodeNext is unchanged;
// this is purely an additional accessor onto the same decode.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hushkeyboard_LlamaSession_nativeTopKPieces(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    jclass stringClass = env->FindClass("java/lang/String");
    if (state == nullptr || state->vocab == nullptr || state->top3Count == 0) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }
    jobjectArray result = env->NewObjectArray(state->top3Count, stringClass, nullptr);
    for (int i = 0; i < state->top3Count; i++) {
        char piece[128];
        int n = llama_token_to_piece(state->vocab, state->top3[i], piece, sizeof(piece), 0, true);
        env->SetObjectArrayElement(result, i, env->NewStringUTF(std::string(piece, n > 0 ? n : 0).c_str()));
    }
    return result;
}

// Phase 5 Session 71 (slice 1b). Context-aware autocorrect rescoring: given a
// prefilled context (nativePrefill already ran, so state->ctx holds the context
// KV-cache and state->nPast = number of context tokens), score each candidate
// word as the length-normalized conditional log-prob log P(w | context). The
// caller compares these to override the fast edit-distance-1 pick only when the
// model strongly prefers a different correction.
//
// Privacy posture (SECURITY.md rule 3/4): this reads candidate strings in to
// tokenize them, but logs/persists/retains NOTHING and returns ONLY doubles —
// no candidate text, no context text, crosses back to Kotlin. Same single owner
// thread as prefill/decode (the UAF guard); the caller serializes the calls.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hushkeyboard_LlamaSession_nativeScoreCandidates(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray jCandidates) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    if (state == nullptr || state->ctx == nullptr || state->vocab == nullptr) {
        // Invalid/unprefilled handle: return a zero-length array, never crash.
        return env->NewDoubleArray(0);
    }

    jsize nCandidates = env->GetArrayLength(jCandidates);
    jdoubleArray result = env->NewDoubleArray(nCandidates);
    if (nCandidates == 0) {
        return result;
    }

    llama_context *ctx = state->ctx;
    const llama_vocab *vocab = state->vocab;
    const int n_vocab = llama_vocab_n_tokens(vocab);
    const int n_ctx = (int) llama_n_ctx(ctx);
    const int contextPast = state->nPast;

    // The prefill's last-position logits = P(next token | context). The decode
    // loop below overwrites the context's logits buffer, so snapshot the whole
    // n_vocab row ONCE here and reuse it as logits_0 for every candidate's first
    // token. (Captured before the candidate loop, exactly as the spec requires.)
    std::vector<float> prefillLogits(n_vocab);
    {
        const float *src = llama_get_logits_ith(ctx, -1);
        for (int i = 0; i < n_vocab; i++) prefillLogits[i] = src[i];
    }

    llama_memory_t mem = llama_get_memory(ctx);

    for (jsize idx = 0; idx < nCandidates; idx++) {
        double score = -1e9; // sentinel for empty / skipped candidates

        auto jWord = (jstring) env->GetObjectArrayElement(jCandidates, idx);
        if (jWord != nullptr) {
            const char *wordChars = env->GetStringUTFChars(jWord, nullptr);
            // HIGHEST-RISK tokenization decision (measured on device, Session 71):
            // SmolLM2 BPE attaches the leading space to the continuation token, so
            // a word mid-sentence is " word", not "word". Tokenize " " + w with
            // add_special=false (the BOS already lives in the prefilled context).
            std::string piece = std::string(" ") + wordChars;
            env->ReleaseStringUTFChars(jWord, wordChars);

            int n_tok = -llama_tokenize(vocab, piece.c_str(), (int) piece.size(),
                                        nullptr, 0, false, false);
            if (n_tok > 0) {
                std::vector<llama_token> cand(n_tok);
                llama_tokenize(vocab, piece.c_str(), (int) piece.size(),
                               cand.data(), n_tok, false, false);

                // Headroom guard: the context occupies [0, contextPast); scoring
                // this candidate needs contextPast + (n_tok - 1) decoded positions.
                // If that would overrun n_ctx, skip with the sentinel rather than
                // overrun the KV-cache.
                if (contextPast + (n_tok - 1) <= n_ctx) {
                    double logp = 0.0;
                    bool ok = true;
                    const float *logits = prefillLogits.data();
                    for (int j = 0; j < n_tok; j++) {
                        // logits = distribution over the token at position j.
                        // log softmax(logits)[cand[j]] = logits[cand[j]] - logsumexp(logits),
                        // computed stably by subtracting the max logit.
                        float maxLogit = logits[0];
                        for (int v = 1; v < n_vocab; v++) {
                            if (logits[v] > maxLogit) maxLogit = logits[v];
                        }
                        double sumExp = 0.0;
                        for (int v = 0; v < n_vocab; v++) {
                            sumExp += std::exp((double) (logits[v] - maxLogit));
                        }
                        double logSoftmax = (double) (logits[cand[j]] - maxLogit) - std::log(sumExp);
                        logp += logSoftmax;

                        // For all but the last token, decode cand[j] so the next
                        // iteration reads P(cand[j+1] | context, cand[0..j]).
                        if (j + 1 < n_tok) {
                            llama_batch batch = llama_batch_get_one(&cand[j], 1);
                            if (llama_decode(ctx, batch) != 0) { ok = false; break; }
                            logits = llama_get_logits_ith(ctx, -1);
                        }
                    }
                    if (ok) score = logp / (double) n_tok; // length-normalized
                }
            }

            env->DeleteLocalRef(jWord);
        }

        // Rewind the KV-cache back to the clean prefilled context so the next
        // candidate starts fresh. Removes positions [contextPast, inf) for seq 0.
        // state->nPast is left untouched: the caller's session stays as prefilled.
        llama_memory_seq_rm(mem, 0, contextPast, -1);

        env->SetDoubleArrayRegion(result, idx, 1, &score);
    }

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hushkeyboard_LlamaSession_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *state = reinterpret_cast<LlamaSessionState *>(handle);
    if (state == nullptr) return;
    if (state->ctx != nullptr) llama_free(state->ctx);
    if (state->model != nullptr) llama_model_free(state->model);
    delete state;
}
