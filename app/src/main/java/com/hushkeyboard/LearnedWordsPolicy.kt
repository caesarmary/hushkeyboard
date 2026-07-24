package com.hushkeyboard

/**
 * Phase 4 learned-words dictionary: the SECURITY-CRITICAL decision of whether the
 * CURRENT field may be learned from. Pure Kotlin, no Android-framework imports
 * (Gate 8 Option 1) — and because this enforces a SECURITY.md invariant, Gate 8
 * Option 3 (manual-only testing) is FORBIDDEN for it. It is unit-tested.
 *
 * SECURITY.md rule 4 (password-field discipline) is absolute: content typed into a
 * password field must NEVER be recorded into the learned-words dictionary. That is
 * the non-negotiable line this function draws.
 *
 * We draw it slightly WIDER than the bare invariant: learning is excluded from any
 * field [SensitiveFieldChecker.isSensitive] flags — which is password variations
 * PLUS `TYPE_TEXT_FLAG_NO_SUGGESTIONS` fields (e.g. search bars). Rationale:
 * learning PERSISTS typed words to disk, a heavier action than transient
 * prediction. A field that explicitly opts out of suggestions is signalling "do
 * not treat my content as dictionary material", and a search query is exactly the
 * kind of content a privacy keyboard should not quietly keep. This is stricter
 * than rule 4 requires, never looser, so the invariant is preserved with margin.
 *
 * (Contrast: live prediction in [PredictionContext.isEligible] gates only on the
 * narrower [SensitiveFieldChecker.isPasswordField], because prediction reads
 * nothing to disk. Persistence earns the stricter gate.)
 */
object LearnedWordsPolicy {

    /**
     * True if the field described by [inputType] must NOT be learned from. The
     * caller (the store) must check this BEFORE recording any word. A null/unknown
     * EditorInfo must be treated as sensitive by the caller (fail-safe), the same
     * stance taken everywhere else in the keyboard.
     */
    fun isFieldExcludedFromLearning(inputType: Int): Boolean =
        SensitiveFieldChecker.isSensitive(inputType)

    /**
     * Null-aware overload for the framework call site. A null [inputType] means
     * there is no [android.view.inputmethod.EditorInfo] to inspect; the fail-safe
     * is to treat that as sensitive (excluded), the same stance the keyboard takes
     * everywhere else for a missing EditorInfo. Keeping this decision here — pure
     * and unit-tested — rather than as a bare one-liner in the Service means the
     * SECURITY-CRITICAL fail-safe has automated coverage (Gate 8 Option 1).
     */
    fun isFieldExcludedFromLearning(inputType: Int?): Boolean =
        inputType == null || isFieldExcludedFromLearning(inputType)
}
