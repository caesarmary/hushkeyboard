package com.hushkeyboard

// Sensitive-field detection. Pure Kotlin — no Android imports — so it is JVM-testable.
// Constants are copies from android.text.InputType; Android cannot change these values
// without breaking every app that uses them.
object SensitiveFieldChecker {

    private const val TYPE_MASK_VARIATION = 0x00000FF0

    private const val TYPE_TEXT_VARIATION_PASSWORD         = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD     = 0x000000E0
    private const val TYPE_NUMBER_VARIATION_PASSWORD       = 0x00000010

    // Apps that handle their own input (e.g. OTP boxes) set this flag.
    private const val TYPE_TEXT_FLAG_NO_SUGGESTIONS        = 0x00080000

    // Callers must handle the null-EditorInfo case separately; this function only judges
    // the raw inputType integer.
    //
    // isSensitive  — used to decide whether to suppress autocorrect. Returns true for all
    //                password variations AND for TYPE_TEXT_FLAG_NO_SUGGESTIONS (e.g. Google
    //                Search). Autocorrect is unwanted in both cases.
    // isPasswordField — used to decide whether to skip getTextBeforeCursor in long-press
    //                backspace. Returns true ONLY for actual password variations. Reading
    //                a search query carries no privacy risk, so NO_SUGGESTIONS fields are
    //                excluded here and long-press word-deletion works normally in them.
    fun isSensitive(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == TYPE_NUMBER_VARIATION_PASSWORD ||
                inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
    }

    fun isPasswordField(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == TYPE_NUMBER_VARIATION_PASSWORD
    }
}
