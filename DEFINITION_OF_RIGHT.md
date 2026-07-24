# Definition of Right
### A mandatory checklist before adopting any technology, library, framework, or architectural decision.

> This file must be consulted before Claude Code introduces any new dependency or structural choice.
> If a technology cannot pass all gates, it is not approved for use in this project.
> If Claude Code cannot answer a question clearly, that is itself a failing answer.

---

## How to use this

When Claude Code recommends a technology, library, or architectural approach, paste this prompt into the session:

> "Before we proceed, I need you to walk me through the Definition of Right checklist for this decision. Answer each gate clearly and honestly, including the risks. If you cannot answer a gate, say so explicitly."

Then evaluate the answers against each gate below.

---

## Gate 1 — Alternatives Considered

**Ask:** *"What are the alternatives to this choice, and why are you not recommending them?"*

A good recommendation gets stronger when alternatives are named and dismissed with clear reasons.

**Pass criteria:**
- At least two alternatives are named
- Each alternative is dismissed with a specific reason tied to *our* constraints (offline, Android, 5-10hrs/week, 3-month timeline)
- The recommendation is not just the most popular option by default

**Red flags:**
- "This is the standard way to do it" with no further reasoning
- No alternatives mentioned
- Alternatives dismissed for vague reasons ("it's more complex")

---

## Gate 2 — Risk Against Our Constraints

**Ask:** *"What could go wrong with this choice, specifically given our constraints: no internet, Android only, a part-time AI-assisted build, 3-month MVP timeline?"*

Force Claude Code to argue against its own recommendation.

**Pass criteria:**
- At least one concrete risk is identified
- The risk is assessed as acceptable *or* a mitigation is proposed
- The answer is specific to this project, not generic

**Red flags:**
- "There are no significant risks"
- Risks mentioned are generic and not tied to our constraints
- No mitigation offered for a high-severity risk

---

## Gate 3 — Maturity and Adoption

**Ask:** *"How mature and widely used is this technology? Who else uses it in production?"*

For an MVP built part-time with AI assistance, boring and proven beats cutting-edge.

**Pass criteria:**
- Technology has been in production use for at least 3 years, OR
- It is backed by a major organization (Google, Meta, Apache, etc.) with active maintenance
- Real-world adoption examples can be named

**Red flags:**
- "It's relatively new but promising"
- Maintained by a single developer or small team
- Last update was over 12 months ago
- No known production usage at meaningful scale

---

## Gate 4 — Reversibility

**Ask:** *"If this choice turns out to be wrong six months from now, how hard is it to replace?"*

Architectural decisions that are hard to undo deserve the most scrutiny.

**Pass criteria:**
- Replacement path is described concretely
- Impact of replacement is scoped (e.g. "affects only the prediction module, not the whole app")
- It is not a decision that rewrites the entire project if reversed

**Red flags:**
- "It would be very difficult to replace"
- The technology is deeply embedded across multiple layers
- No clear migration path exists

---

## Gate 5 — Maintainability Without Deep Expertise

**Ask:** *"Can this be maintained and debugged in future Claude Code sessions without me needing to deeply learn this technology myself?"*

The stack must remain navigable with AI assistance, without requiring deep specialist expertise to keep it running.

**Pass criteria:**
- The technology is well-documented enough that Claude Code can reference it reliably
- Debugging does not require specialist knowledge unavailable to an AI assistant
- Common errors and fixes are well-indexed (Stack Overflow, official docs, GitHub issues)

**Red flags:**
- "You'd need to understand X deeply to debug issues"
- Poor or sparse documentation
- Niche enough that Claude Code might hallucinate solutions

---

## Gate 6 — Privacy Integrity (Project-Specific)

**Ask:** *"Does this technology introduce any surface that could transmit data externally, even indirectly?"*

This gate is non-negotiable. It exists because privacy is the entire product promise.

**Pass criteria:**
- Technology functions fully offline with zero external calls
- No telemetry, analytics, or crash reporting is bundled by default
- If it has optional connectivity features, those are explicitly disabled in our implementation

**Red flags:**
- Any default network call, even for updates or licensing
- Bundled analytics that must be actively opted out of
- Dependency on an external service even for initialization

---

## Gate 7 — User-Action Safety (Project-Specific)

**Ask:** *"Am I about to ask the user to do something that could violate SECURITY.md or DEFINITION_OF_RIGHT.md, even indirectly?"*

This gate exists because external actions carry risks that are easy to under-scrutinize when moving fast with AI assistance. The safety evaluation must be made explicitly, on the user's behalf, before any action is requested.

This gate applies to any instruction directed at the user: downloading a file, running a terminal command, installing a tool, changing a system setting, visiting a URL, or any other action outside normal file editing.

**Before asking the user to take any external action, Claude Code must explicitly answer:**

1. What does this action do, in plain language?
2. What does it change on the machine, project, or accounts?
3. Could it introduce a new dependency, network call, permission, or piece of telemetry — even on the development machine?
4. Could a compromised version of this action (e.g. a tampered download, a malicious URL) harm the project or the user's machine?
5. Has the source been verified as trustworthy (not just assumed)?

**Pass criteria:**
- All five questions are answered before the user is asked to act
- The action is confirmed safe against SECURITY.md and Gates 1–6
- If any question cannot be answered with confidence, that uncertainty is stated explicitly

**Red flags:**
- Asking the user to run a command without explaining what it does
- Asking the user to download a file without verifying the source
- Presenting an action as routine when it has security implications
- Assuming that "development machine only" means "no rules apply"

**Note on development machine actions:** A security failure on the development machine can propagate into the app (e.g. a tampered word list, a compromised build tool, a leaked signing key). Development-side actions are not exempt from scrutiny.

---

## Gate 8 — Testability of Framework-Coupled Code

**Trigger:** Any code written in, or moved into, a class that inherits from or directly calls Android framework types — including but not limited to `InputMethodService`, `Service`, `Activity`, `View`, `Context`, `EditorInfo`, and `InputConnection`. These types cannot be instantiated in a standard JVM test (JUnit), so framework-coupled logic has no automated test coverage by default.

**Why this gate exists:** Phase 2 produced M7 (password-field protection failure, a security-critical bug) and M8 (backspace re-expansion). Both lived in `HushKeyboardService.kt`. Both were caught only by manual testing on a physical device. If they had shipped, no automated check would have caught them. That is not a sustainable model.

**Ask:** *"How will this framework-coupled code be tested, and is that decision documented before the code is written?"*

**The three options. Choose one, in writing, before writing the code.**

**Option 1 — Extract pure logic.** Move as much logic as possible into a plain Kotlin class with no Android imports. That class can be JUnit-tested like any other class. The Android-coupled code becomes a thin wrapper that calls into it. This option requires no new dependencies.

**Option 2 — Robolectric.** Add a test-only dependency that simulates the Android runtime on the JVM, allowing Android-coupled code to be tested without a device. This option requires a full Gate 1–7 review of Robolectric before it can be approved. Robolectric cannot be used just because it exists; it must pass the full checklist.

**Option 3 — Accept manual-only testing.** Deliberately leave the code without automated coverage and rely on a documented manual test plan. This option requires documenting in writing: (a) the decision, (b) which specific manual tests cover the logic, and (c) why Options 1 and 2 were rejected for this case. **Option 3 is forbidden for security-critical code.** Any logic that protects a SECURITY.md invariant (password-field discipline, no keystroke logging, etc.) must have automated coverage via Option 1 or Option 2.

**Pass criteria:**
- One of the three options is chosen and written down in `the project design notes` before framework-coupled code is written.
- If Option 1: the extracted class has automated tests before the feature is marked complete.
- If Option 2: a full Gate 1–7 review of Robolectric is completed and approved first.
- If Option 3: the manual test plan is updated with the specific manual tests covering the untested logic. The logic must not touch any SECURITY.md invariant.

**Red flags:**
- Framework-coupled code is written without any prior decision on testing strategy.
- Option 3 is applied to security-critical logic.
- The extracted class from Option 1 holds no real logic — the refactor was done in name only, moving a tangle of Android calls rather than isolating testable behaviour.

**Retro-application:** Gate 8 applies to existing code as well as new code. `HushKeyboardService.kt` is the first case where this gate must be retro-applied. The decision for that file — Option 1, extract pure logic — is logged in `the project design notes` under the Mini Phase 1 entry.

---

## Decision Log

After a technology passes all gates, log it here. This becomes the project's approved technology record.

| Date | Technology | Purpose | Who approved | Notes |
|------|------------|---------|--------------|-------|
| 2026-06-07 | TensorFlow Lite (`org.tensorflow:tensorflow-lite`, core artifact only — no support/task libraries) | On-device inference runtime for Phase 4 word prediction | Project owner | Full 8-gate review in Session 19. Chosen over ONNX Runtime: smaller APK footprint, stronger Android-native documentation, larger ecosystem of mobile-optimized text models. Manifest/permission verification **CLOSED Session 25 (2026-06-07): PASS.** Pinned exactly **2.16.1** (last real AAR); its manifest + its only transitive dep (`tensorflow-lite-api:2.16.1`) declare **zero permissions** — no INTERNET/ACCESS_NETWORK_STATE; merged-APK manifest confirmed clean post-build. **Do NOT use 2.17.0** — it is a relocation stub to `com.google.ai.edge.litert:litert`, which carries Play-based model-download machinery (`FOREGROUND_SERVICE_DATA_SYNC`, `com.google.android.play:ai-delivery`). NOTE: on-device the 2.16.1 runtime cannot *execute* the current model (op-version skew, "builtin_code 206"); model re-conversion to the 2.16.1 op set is the chosen path (Session 25). **RESOLVED Session 26 (2026-06-07): on-device execution now CORRECT on the A52s with this exact 2.16.1 runtime.** Required re-converting the model to fit the 2024 runtime: `enable_hlfb=False` (drops op 206) + `quantize='weight_only_int8'` for the whole model (the 2026 dynamic-range int8 + int8-embedding kernels miscompute on the *ARM* 2.16.1 build — all-zeros from the embedding lookup, then ~300×-wrong logits from dynamic-range FCs; weight-only runs all math in float, which the ARM kernels handle correctly). Runtime choice unchanged and validated end-to-end (smoke test PASS, merged manifest still network-permission-free). Perf caveat: weight-only is float-compute → slower than dynamic-int8; decode latency to be measured when wiring live prediction (Session 27). |
| 2026-06-12 | Raw Android Keystore + AES-256-GCM (platform API: `java.security.KeyStore` "AndroidKeyStore" provider + `javax.crypto.Cipher`) — **no new dependency** | Encryption-at-rest primitive for the Phase 4 learned-words dictionary (SECURITY.md rule 5) | Project owner | Full 8-gate review in Session 34 (2026-06-12). **Decision-only — no code, no dependency added yet; implementation is a separate later session.** Chosen over (a) `androidx.security:security-crypto` — **rejected: officially deprecated by Google in 2024** (last stable 1.0.0, April 2021), fails Gate 3 active-maintenance for brand-new code; SECURITY.md rule 5 permits an "equivalent reviewed-and-approved primitive" so the deprecated lib is not mandatory; and (b) Google Tink (`com.google.crypto.tink:tink-android`, Apache 2.0, Google-maintained, offline, no network) — viable fallback, rejected here for adding a dependency + protobuf-lite transitively when the use case is narrow. Raw Keystore wins on: **zero new dependencies** (cleanest Gate 6 — nothing to scan), key is hardware-backed and **non-exportable** (never leaves device), and we **own and document the on-disk envelope** `[12-byte IV][ciphertext][16-byte GCM tag]` — most directly satisfying rule 5's "documented, non-opaque format". Main risk (hand-written AES-GCM glue) mitigated by: fresh random IV per write (never reused), and treating the dictionary as a **disposable cache** — any decrypt/key-invalidation failure → discard and rebuild, which neutralizes the `KeyPermanentlyInvalidatedException` foot-gun. Gate 8 plan: Option 1 (extract pure serialization/format + password-field-exclusion logic for JUnit; thin Keystore wrapper verified on-device). Network: none added (SECURITY.md rule 1 untouched). minSdk 26 supports AES-GCM via AndroidKeyStore (available since API 23). |
| 2026-06-07 | SmolLM2-135M (Hugging Face "SmolLM" team, Apache 2.0) — to be converted to `.tflite` for use with the runtime above | Pre-trained language model for Phase 4 next-word prediction | Project owner | Full 8-gate review in Session 19. Chosen over DistilGPT-2 (older 2019-era architecture, ~310 MB unconverted, no purpose-built fit for short mobile text) and over Gemma 3 270M / Qwen3-0.6B (require a separate, not-yet-reviewed runtime — LiteRT-LM — with an open and unresolved network-access question). Honest open risk (as stated Session 19): "no prior art exists for converting this exact model to TFLite — we will be the first." **Updated Session 21 (2026-06-07): this is no longer accurate.** Google's converter (renamed `ai-edge-torch` → `litert-torch`, Jan 2026) ships an official `smollm` example targeting our exact checkpoint `HuggingFaceTB/SmolLM2-135M-Instruct` (v2 path), with a bundled output-verification script. Converting this model is therefore a Google-maintained, tested recipe — the residual risk is execution (the official path re-authors the architecture and loads HF weights; not literally one-click), not blazing a trail. Mitigation retained regardless: a hard go/no-go checkpoint after two focused conversion attempts — **reconfirmed by the user as written, Session 20 (2026-06-07)**, operative in Stage 2 (conversion). APK size ceiling was revised from ~80 MB to ~200 MB the same session (see the project backlog), based on real-world comparables (FUTO Keyboard ships at ~95–124 MB) — this substantially eased the size pressure on this choice. **Gate 7 walkthrough completed in writing, Session 20** (the project design notes): dev-machine ML tooling split into Stage 1 (venv + `torch` + `transformers`, approved) and Stage 2 (conversion tooling, own Gate 7 pending — open Windows/Linux converter-support question). |
| 2026-06-25 | `david47k/top-english-wordlists` `top_english_words_lower_50000.txt` (CC BY 3.0, Google Books Ngrams 1950–2012) — static bundled asset, **no new dependency** | Frequency word list backing `Autocorrect.kt`, replacing Hermit Dave `en_50k` | Project owner | Full licence survey across five candidate sources (Session 15); this swap executed Session 61. Replaces Hermit Dave's `en_50k` (CC BY-SA 4.0 — ShareAlike, incompatible with this project's licensing needs) — the prior list was never cleared for that reason. Chosen over `hackerb9/gwordlist` (also CC BY 3.0 but needs trimming from 246k entries) and `Maximax67/English-Valid-Words` (Unlicense, but the public-domain claim rests on a less certain legal theory than Google's explicit CC BY 3.0 grant). Verified directly against the source repo's README at swap time ("licensed under a Creative Commons Attribution 3.0 Unported License"), not just trusted from the Session 15 research. Drop-in for `Autocorrect.kt`'s existing parser — same one-word-per-line, frequency-ordered, lowercase format; no code change. All 22 `AutocorrectTest` cases pass unchanged. **Open obligation:** CC BY 3.0's attribution requirement is distribution-triggered, same pattern as the SmolLM2 Apache 2.0 note above — add a credit line ("Word frequency data: Google Books Ngrams via david47k/top-english-wordlists, CC BY 3.0") to a licences/credits surface when the app is distributed. Satisfied in `CREDITS.md`. |
| 2026-06-30 | Donate/support link via `Intent.ACTION_VIEW` to a static `https://` URL (Android platform API, present since API 1) — **no new dependency, no new permission** | An optional single tappable row in `SettingsActivity`'s "About" section opens a support/donation page in the user's browser. Never required; no feature is gated on it. | Project owner | Full 8-gate pass in Session 85. **Verdict: PASS, WITH CONDITIONS.** Crux is Gate 6 / SECURITY rule 1: `ACTION_VIEW` delegates to the browser (a separate process) and neither requires nor grants `INTERNET` — the manifest stays permission-free (verified: zero `<uses-permission>` today), the keyboard opens no socket, the promise holds. Alternatives rejected: in-app WebVIEW (in-process network surface, needs `INTERNET`, hard-fails rule 1) and Play Billing tip (new dependency + in-app payment surface); bundled QR asset kept as a hardened-build fallback. Gate 4 blast radius = one URL constant + one row + one click handler, delete-to-revert. Gate 8: handler is Activity-coupled but touches no SECURITY invariant (no field/keystroke/password path); the security-relevant fact — no `INTERNET` — is enforced by the manifest and covered by the existing merged-manifest verification, so the wrapper is Option 3 (manual verification). **Conditions (carry into implementation):** (1) bare static `https` URL, no tracking/UTM params, no URL shortener; (2) opened only in the external browser, never an in-app WebView; (3) fires only on explicit user tap, never automatically; (4) manifest stays permission-free; (5) verify the exact URL before each release (delivered only via a full app update, never a hot-fix). |
| 2026-06-27 | llama.cpp candidate-scoring native entry point (new `nativeScoreCandidate` in `hush_llama_jni.cpp` + thin `LlamaSession` wrapper method) — **NOT a new dependency; new surface on the already-approved llama.cpp runtime + already-shipped SmolLM2-135M model** | Phase 5 propose-then-rank: lets the dictionary/learned-words layers PROPOSE candidates and the SmolLM2 model RANK them by sentence context, by returning a candidate word's summed token log-probability against the already-prefilled KV-cache. Fixes the context-blind weakness of `Autocorrect.kt` and extends context ranking to word completion. | Project owner | Full 8-gate review this session (Session 70; write-ups in the project design notes). **Reuses approved tech** — SmolLM2 (Apache 2.0) and llama.cpp are already in this log; the only new thing is one additive native function exposing a candidate's score, because today the boundary returns decoded strings + top-3 token ids (`topK3`/`nativeTopKPieces`) and never crosses raw logits to Kotlin. Verdict: **PASS, WITH CONDITIONS.** Six gates clean PASS (1, 3, 4, 6, 7, 8), two PASS-WITH-MITIGATION (2 latency, 5 native maintainability), zero FAIL. Gate 4 (the live one): blast radius = new native fn + thin wrapper + one pure-Kotlin rescorer; removing the rescorer reverts cleanly to current Phase 4 behaviour because `SuggestionFacilitator.merge` already takes unranked candidates and `Autocorrect`/learned-words work standalone — reversal is a subtraction, not a migration. Gate 6: no network/permission/telemetry added (SECURITY.md rule 1 untouched, manifest unchanged); rule-4 password gate stays in `HushKeyboardService`, rescorer invoked ONLY downstream of that gate (SECURITY.md rule 4 extended this session to cover the new surface). Gate 8: **Option 1 (extract pure logic)** — ranking/threshold/fallback in a JUnit-testable pure-Kotlin rescorer; native call is a thin wrapper, same posture as `SuggestionFacilitator`/`LlamaSession`. **Conditions (carry into implementation):** (1) native fn additive, does not modify existing accessors, reuses the existing `LlamaSessionState` KV-cache; (2) rescorer pure-Kotlin, no Android imports, no logging of context/candidates; (3) invoked only inside the existing password-gated suggestion path, never upstream of it; (4) on-device golden-value checks for the native scoring math (no JVM coverage by nature); (5) per-keystroke latency measured on the A52s before the rescorer is enabled by default, candidate set capped at the proposer layer. |

---

## Escalation Rule

If you are unsure whether a technology has passed a gate, do not approve it in-session.

Instead: copy the Claude Code response and paste it into a fresh Claude.ai conversation with this prompt:

> *"Claude Code recommended [technology] for [purpose]. Here is its justification: [paste]. Does this pass scrutiny? What would a skeptical senior engineer say?"*

Use that second opinion before approving. This takes 5 minutes and has saved many projects from expensive mistakes.

---

*This document is part of the project's Definition of Right protocol.*
*It applies to every session, without exceptions.*
