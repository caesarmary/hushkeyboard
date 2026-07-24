# hushkeyboard

A privacy-first Android keyboard. The product promise is simple: **this keyboard cannot leak what you type, because it cannot reach the internet at all** — while still offering smart features (autocorrect, next-word prediction) that most privacy keyboards lack.

---

## The core guarantee

hushkeyboard declares **no `INTERNET` permission** in its manifest — and no `ACCESS_NETWORK_STATE` or any other networking permission. There is no code path that opens a socket, no analytics SDK, no crash reporter, no "phone home." A keyboard that has no way to reach the network cannot exfiltrate your keystrokes, by construction rather than by policy.

Everything smart about it — autocorrect, word prediction, next-word suggestions — runs **fully on-device**. The language model is compiled into the app and executed locally; it has no more network access than the rest of the app, which is to say none.

The full, honest threat model (what this does and does **not** defend against) is in [`SECURITY.md`](SECURITY.md). The plain-language privacy policy is in [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).

---

## Features

- **Full QWERTY keyboard** with shift / caps-lock, a two-page symbol layer, and long-press accented-character picker (à á â ä …).
- **Offline autocorrect** backed by a bundled frequency-ordered English word list, using Optimal String Alignment distance (so `teh → the` is a single edit).
- **On-device next-word prediction** and suggestion strip, powered by a small transformer (SmolLM2-135M) running through **llama.cpp** compiled to native code.
- **Context-aware autocorrect (propose-then-rank):** dictionary and learned-word layers *propose* candidates; the on-device model *ranks* them by sentence context.
- **Encrypted learned-words dictionary:** words you use often are remembered — stored only on-device, encrypted at rest with a hardware-backed Android Keystore key, and **never** including anything typed in a password field. Clearable at any time from Settings.
- **Password / sensitive-field discipline:** in password and no-suggestions fields, nothing is buffered, corrected, predicted, learned, or read back.
- **A "hardened" build variant** that compiles the learned-words feature out entirely.

---

## How it works

hushkeyboard is an **Input Method Editor (IME)** — an Android app that replaces the system keyboard. Its heart is `HushKeyboardService`, a `Service` Android invokes whenever a text field becomes active.

A guiding architectural rule (see [`DEFINITION_OF_RIGHT.md`](DEFINITION_OF_RIGHT.md), Gate 8): **framework-coupled code is kept thin, and real logic is extracted into pure Kotlin classes with no Android imports** so it can be unit-tested on the JVM. Security-critical logic (password-field discipline especially) is required to have automated coverage.

That shows up across the codebase:

| Concern | Pure, JVM-tested logic | Thin Android/native wrapper |
|---|---|---|
| Word buffer & correction suppression | `InputStateManager` | `HushKeyboardService` |
| Shift / symbol state machines | `ShiftStateManager`, `SymbolStateManager` | key handlers |
| Autocorrect | `Autocorrect`, `CandidateGenerator`, `ConfusionSets`, `EditCost` | — |
| Context re-ranking | `ContextRescorer`, `PredictionContext` | `LlamaSession` (JNI) |
| Learned words | `LearnedWords`, `LearnedWordsCodec`, `LearnedWordsPolicy` | `LearnedWordsCipher`, `LearnedWordsStore` (Keystore) |
| Sensitive-field detection | `SensitiveFieldChecker`, `NumpadFieldChecker` | — |

The native model layer lives in `app/src/main/cpp` (`hush_llama_jni.cpp` + a pinned llama.cpp), exposed to Kotlin through `LlamaPredictor` / `LlamaSession`.

---

## Project structure

```
app/src/main/
├── java/com/hushkeyboard/   # Kotlin: IME service, autocorrect, prediction, learned words, UI
├── cpp/                     # llama.cpp JNI bridge (native inference)
├── assets/                  # bundled word list + in-app legal text
└── res/                     # layouts, drawables, themes (light + dark)
app/src/test/                # JVM unit tests (pure-logic classes)
app/src/androidTest/         # instrumented on-device tests (native/model/Keystore)
```

---

## Building

### Prerequisites
- Android Studio / Android SDK, **minSdk 26 (Android 8.0)**.
- A device or emulator. The prediction feature targets **arm64-v8a**.

### Two artifacts are not committed
The ~138 MB language model and the ~50 MB compiled native libraries are deliberately **gitignored** (kept out of the repo to keep it lean). The app builds and runs the *keyboard* without them; the *prediction* feature needs them. To produce them:

**1. The model — `app/src/main/assets/smollm2_135m_instruct_q8_0.gguf`**
Convert the Apache-2.0 [SmolLM2-135M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct) checkpoint to GGUF Q8_0 using llama.cpp's converter:
```
python3 convert_hf_to_gguf.py <hf_checkpoint_dir> \
  --outtype q8_0 \
  --outfile smollm2_135m_instruct_q8_0.gguf
```

**2. The native libraries — `app/src/main/jniLibs/arm64-v8a/`**
Cross-compile llama.cpp (pinned at tag `b9688`, commit `4b4d13ae721e5eb79b749ca2c6feefd157f90ed7`) for Android with the NDK (r27) + CMake:
```
-DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake
-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
-DBUILD_SHARED_LIBS=ON -DGGML_LLAMAFILE=OFF -DLLAMA_CURL=OFF
-DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF
-DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_COMMON=OFF
```
Build only the `ggml` and `llama` targets, then copy `libggml-base.so`, `libggml-cpu.so`, `libggml.so`, and `libllama.so` into `app/src/main/jniLibs/arm64-v8a/`. (Those flags deliberately exclude the server, CLI, and the bundled HTTP library — nothing networking-capable is linked in.)

### Build & install
```
./gradlew installDebug
```
Then enable hushkeyboard in **Settings → General management → Keyboard**.

There is also an `assembleHardened` variant that compiles out the learned-words feature.

---

## Testing

See [`TESTING.md`](TESTING.md). In short: pure-logic classes are covered by JVM unit tests; native, model, and Keystore code is covered by instrumented on-device tests; framework-coupled UI behaviour has a documented manual test plan.

---

## Credits & licences

Third-party components (SmolLM2, llama.cpp, the word-frequency list) and their licences are listed in [`CREDITS.md`](CREDITS.md).

## Status & license

A personal, part-time project built with AI assistance, taken from an empty project to on-device neural word prediction over a series of phases. It is shared here as a portfolio project. The author's own code is released under the **MIT License** (see [`LICENSE`](LICENSE)); bundled third-party components keep their own licences — see [`CREDITS.md`](CREDITS.md).
