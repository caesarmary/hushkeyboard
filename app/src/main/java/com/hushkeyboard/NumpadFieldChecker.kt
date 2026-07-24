package com.hushkeyboard

// Numeric-field detection. Pure Kotlin — no Android imports — so it is JVM-testable.
// Constants are copies from android.text.InputType; Android cannot change these values
// without breaking every app that uses them.
//
// Returns true when the host app declares a numeric-only field class and the field is
// NOT a password variation. Those fields should show the numpad layout instead of QWERTY.
object NumpadFieldChecker {

    private const val TYPE_MASK_CLASS     = 0x0000000F
    private const val TYPE_MASK_VARIATION = 0x00000FF0

    // Input classes that indicate the field expects only numbers.
    private const val TYPE_CLASS_NUMBER   = 0x00000002
    private const val TYPE_CLASS_PHONE    = 0x00000003
    private const val TYPE_CLASS_DATETIME = 0x00000004

    // Password variation for TYPE_CLASS_NUMBER — must NOT show the numpad.
    // These fields use the full keyboard with autocorrect suppressed (existing behaviour).
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    // Returns true when the field is a numeric class AND is not a password variation.
    // Callers must handle the null-EditorInfo case separately; this function only judges
    // the raw inputType integer.
    //
    // TYPE_NUMBER_VARIATION_PASSWORD (0x00000010) is only meaningful for TYPE_CLASS_NUMBER.
    // The same bit value is TYPE_DATETIME_VARIATION_DATE for TYPE_CLASS_DATETIME — it is
    // not a password flag for datetime fields. The password guard therefore applies only
    // when the class is TYPE_CLASS_NUMBER.
    fun isNumericField(inputType: Int): Boolean {
        val fieldClass = inputType and TYPE_MASK_CLASS
        when (fieldClass) {
            TYPE_CLASS_NUMBER -> {
                // Exclude numeric password fields — they must keep the full keyboard.
                val variation = inputType and TYPE_MASK_VARIATION
                return variation != TYPE_NUMBER_VARIATION_PASSWORD
            }
            TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME -> return true
            else -> return false
        }
    }
}
