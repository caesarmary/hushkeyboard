# Testing approach

Testing on this project is shaped by one architectural rule: **push logic out of Android-framework classes into pure Kotlin, so it can be tested on the JVM** — and require automated coverage for anything that protects a security invariant. (This is Gate 8 in [`DEFINITION_OF_RIGHT.md`](DEFINITION_OF_RIGHT.md).) Android-coupled classes (`Service`, `Activity`, `View`) can't be instantiated in a plain JUnit test, so the logic that matters is deliberately kept out of them.

That produces three layers of tests.

## 1. JVM unit tests (`app/src/test`)

Fast, no device needed. Each pure-logic class has a matching test class:

- **Input & layout state:** `InputStateManagerTest`, `ShiftStateManagerTest`, `SymbolStateManagerTest`, `AlternateCharMapTest`
- **Autocorrect engine:** `AutocorrectTest`, `AutocorrectNeighborsTest`, `AutocorrectJudgeTest`, `CandidateGeneratorTest`, `ConfusionSetsTest`, `EditCostTest`
- **Prediction / re-ranking:** `PredictionContextTest`, `ContextRescorerTest`, `SuggestionFacilitatorTest`
- **Learned words:** `LearnedWordsTest`, `LearnedWordsCodecTest`, `LearnedWordsPolicyTest`
- **Sensitive-field detection:** `SensitiveFieldCheckerTest`, `NumpadFieldCheckerTest`
- **Misc:** `LegalTextFormatterTest`

The sensitive-field and learned-words-policy tests matter most: they lock in the password-field discipline that `SECURITY.md` treats as non-negotiable.

Run them with:
```
./gradlew testDebugUnitTest
```

## 2. Instrumented on-device tests (`app/src/androidTest`)

Some logic genuinely can't run on the JVM — the native model, the JNI bridge, and the hardware-backed Keystore encryption. Those are verified on a real device/emulator:

- `LlamaSessionDeviceTest`, `LlamaPredictorDeviceTest` — the native inference session loads and produces sane output.
- `LlamaScoreCandidatesDeviceTest` — the propose-then-rank scoring entry point returns correct golden values.
- `LearnedWordsStoreDeviceTest` — encrypt → persist → decrypt round-trips through the real Android Keystore, and a corrupt/invalidated store fails safe (discard-and-rebuild rather than crash).
- `AutocorrectRegressionHarnessTest` — a regression harness over a batch of correction cases.

Run them with a device attached:
```
./gradlew connectedAndroidTest
```

## 3. Manual test plan

Framework-coupled UI behaviour that isn't worth (or possible) to automate — touch handling, popup positioning, visual state on the live keyboard — is covered by a written manual test plan executed on a physical device before a change is considered done. Where a manual-only choice touches a security invariant, the rule is explicit: it doesn't get to be manual-only. Security-critical logic must live in the JVM-tested layer.

---

The result is that the parts most likely to break quietly — a password field mis-detected, a learned word leaking where it shouldn't, the model scoring the wrong candidate — are the parts with the strongest automated coverage.
