# SECURITY.md

The technical security invariants of hushkeyboard.

This file defines what the product must guarantee, what it does not guarantee, and the non-negotiable rules that protect those guarantees. Every dependency, every code change, every permission request must be checked against this document.

If a proposed change would violate any rule below, Claude Code must stop and flag it before proceeding.

---

## Threat model

Being honest about what hushkeyboard defends against — and what it does not — is itself a security practice. Vague or overstated claims lead to bad design choices.

### What hushkeyboard defends against

- **Keystroke exfiltration over the network.** The keyboard cannot send data anywhere because it cannot reach the network at all. This is the central guarantee.
- **Silent telemetry, analytics, or "phone home" behavior.** Not from us, not from any dependency.
- **Keystroke logging to disk.** Nothing the user types is written to persistent storage in a form that could be read back later. The only exception is the user's learned-words dictionary, which is subject to strict rules below.
- **Leakage of password-field content.** Anything typed into a password field is treated as untrusted-to-itself: not learned from, not predicted on, not stored.
- **Excess permissions.** The app declares the minimum permissions necessary to function as a keyboard. Every permission request is treated as a serious decision.
- **Dictionary or model update attacks.** Because the app does not connect to the network, it cannot fetch updated dictionaries or models that could be tampered with. Updates are delivered only through the Play Store as full app updates.
- **Cross-app leakage.** No data is exposed to other apps via Android content providers, broadcasts, or shared storage.

**Phase 5 native scoring surface.** Context-aware autocorrect adds a native (JNI/C++) entry point that scores a candidate word against the already-prefilled model context. It reads no field data that next-word prediction does not already read, runs behind the same password/sensitive-field gate, and returns only a numeric score. It is new native surface on the already-approved on-device model and runtime (SmolLM2 + llama.cpp, see the `DEFINITION_OF_RIGHT.md` Decision Log) — not a new dependency, and still fully offline (rule 1 untouched: the model cannot reach the network because the app has no network permission).

### What hushkeyboard does NOT defend against

These are real threats. The keyboard alone cannot defeat them, and pretending otherwise leads to security theater. Users on a compromised device need protection at a layer below the application layer (a clean OS, hardware security, etc.).

- **A compromised operating system or rootkit.** An attacker with kernel-level access can read app memory, log touch events before they reach the keyboard, replace the keyboard binary on disk, or capture the screen. No application can defend against this.
- **Forensic tools with hardware unlock capability** (Cellebrite, Graykey, etc.) operating on a seized and unlocked device.
- **Other malicious apps with elevated privileges** — e.g. an accessibility-service abuser already installed on the device.
- **Side-channel attacks** — electromagnetic emanations, acoustic analysis of typing sounds, camera-based shoulder-surfing.
- **Coercion of the user**, or anything else outside the device.
- **Apps that misreport their input field type.** Android provides a standard API (`inputType` in `EditorInfo`) that apps use to declare whether a field is a password field. hushkeyboard reads this declaration and disables autocorrect accordingly. If an app deliberately or incorrectly declares a password field as a normal text field (e.g. `inputType = TYPE_CLASS_TEXT` with no password variation), the keyboard cannot detect this — it sees what the app reports, not what the app displays. This is a non-conformance issue in the app, not a defect in the keyboard. Confirmed example: Facebook's Android login password field (as of May 2026) reports `inputType = 1` (plain text) to all keyboards. Gboard and every other keyboard on the device are subject to the same limitation.

This list is intentional. A future hardened build may expand the in-scope list (e.g. by adding reproducible builds, memory-zeroing, no-disk-persistence modes), but those are deliberate, scoped extensions — not unbounded promises.

---

## Non-negotiable invariants

These rules are absolute. Violations are not "trade-offs to discuss"; they are stop-the-line events.

### 1. No network access, ever

- The `AndroidManifest.xml` must never declare `<uses-permission android:name="android.permission.INTERNET" />`.
- The `AndroidManifest.xml` must never declare `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` or any other permission that exposes network state.
- No code path may attempt a network call, even one that is "guaranteed to fail" without the permission. Such code is dead weight and a future foot-gun.
- No background services, broadcast receivers, scheduled jobs, or work managers may be registered for any purpose connected to networking.

**Clarification — a user-initiated external link does not breach this rule.** The app may carry a static "support development / donate" link in its settings menu. Tapping it fires an Android `ACTION_VIEW` intent against a hardcoded `https://` URL, which Android hands to the user's own browser — a separate app and process. The keyboard process opens no socket and makes no network call; `ACTION_VIEW` neither requires nor grants the `INTERNET` permission, so the manifest stays permission-free. The only thing that leaves the device is the inherent fact that the user chose, by their own deliberate tap, to open a payment page — no keystroke or typed content is ever attached. Conditions that keep this true: the URL is a bare static link with **no tracking/UTM parameters and no URL shortener** (shorteners log clicks and add a redirect hop), it is opened **only in the external browser** (never an in-app WebView, which would be an in-process network surface requiring `INTERNET`), and it fires **only on an explicit user tap**, never automatically. Full DEFINITION_OF_RIGHT pass documented in that file's Decision Log.

### 2. No telemetry, no analytics, no crash reporting

- No analytics SDK (Firebase Analytics, Crashlytics, Sentry, etc.) may be added under any circumstance.
- No "anonymous usage statistics" feature, even opt-in. The product promise is incompatible with the existence of such a feature.
- Crash diagnosis is performed locally during development only and never automated.

### 3. No keystroke logging

- Keystrokes must never be written to system logs (`Log.d`, `Log.i`, etc.), even in debug builds. Android's logcat is readable in some development scenarios and is a recognized leakage path.
- Keystrokes must never be written to plain files for any reason — not for debugging, not for "improving suggestions," not for crash recovery.
- The only persistent storage of typed content is the learned-words dictionary, governed by rule 5.

### 4. Password-field discipline

- When the Android input system signals that the current text field is a password field (via `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`, and related flags), the keyboard must:
  - Not record any keystroke into the learned-words dictionary.
  - Not run prediction or autocorrect against the input in a way that exposes it elsewhere.
  - Not retain the typed content in memory beyond what is strictly required to deliver the keystroke to the OS.
- The same discipline applies to **context-aware autocorrect and word completion** (Phase 5). When the model is used to re-rank candidate words by sentence context, the surrounding field text (the "context") and the candidate words are both treated as protected input:
  - The sentence context fed to the model for re-ranking must be read **only after** the field has been confirmed non-sensitive, using the same gate ordering as next-word prediction (field-type check before any `getTextBeforeCursor` call).
  - Context-aware autocorrect must be suppressed in password fields and in any field already excluded from autocorrect today (i.e. the broader sensitive-field rule, not only password variations). Adding model re-ranking must not widen the set of fields whose text is read.
  - The native scoring call must not log, persist, or retain the candidate words or the context beyond the single scoring operation. Only a numeric score may cross back from native code to Kotlin; raw model probabilities are not otherwise exposed.
  - Candidate words drawn from the learned-words dictionary are themselves protected user content; the password/sensitive gate covers candidate generation as well as the scoring call.

### 5. Learned-words dictionary discipline

A keyboard that cannot learn new words feels broken. So a learned-words dictionary is allowed — but under strict rules:

- Stored only in the app's private internal storage (not external, not shared).
- Stored encrypted at rest, with a key managed by Android's `EncryptedSharedPreferences` / `EncryptedFile` mechanism (or equivalent reviewed-and-approved primitive).
- Never includes content typed in password fields.
- The user can clear the dictionary at any time from the app's settings, with a single confirmed action.
- The dictionary file format is documented and verifiable; no opaque binary blobs from third parties.

### 6. Minimum permissions

The app declares only the permissions strictly required to function as an Android Input Method. Any new permission request requires:

- An explicit justification documented in this file under "Permission decisions log."
- A `DEFINITION_OF_RIGHT.md` review.
- Explicit user approval before being added to the manifest.

### 7. Dependency discipline

- Every new dependency must pass `DEFINITION_OF_RIGHT.md`.
- Every new dependency must be inspected for: declared permissions, network calls, telemetry, transitive dependencies.
- Closed-source dependencies are forbidden.
- Dependencies that fetch updates at runtime (e.g. via remote config) are forbidden.

### 8. Memory hygiene

- Buffers holding user input should not be retained longer than needed to deliver the keystroke.
- This is a best-effort rule on the JVM (which does not allow deterministic memory zeroing), and is acknowledged as imperfect. Where Android offers stricter primitives (e.g. `CharArray` over `String` for sensitive content), they are preferred.
- This rule is documented honestly: it raises the cost of certain attacks, it does not eliminate them.

### 9. Build integrity

- Release builds are signed with a key that is stored offline, never committed to the repository, and never shared.
- The release signing key is backed up securely outside the development machine.
- (Future, when relevant) Reproducible builds are a goal so that users can verify a Play Store binary matches the published source code.

---

## Permission decisions log

Every Android permission added to the manifest is logged here, with justification.

| Date | Permission | Justification | Approved by |
|------|------------|---------------|-------------|
| | | | |

The expected steady-state of this table is: **empty, or close to empty.** A keyboard does not legitimately need much.

---

## Review cadence

This file is reviewed:

- At the end of every phase (Phase 0, Phase 1, Phase 2, etc.).
- Before any release to a public channel (Play Store, F-Droid, sideload).
- Whenever a new dependency is added.
- Whenever a new permission is requested.

---

*This file defines what hushkeyboard must guarantee. `DEFINITION_OF_RIGHT.md` defines how technical decisions are evaluated. Both apply, without exceptions.*
