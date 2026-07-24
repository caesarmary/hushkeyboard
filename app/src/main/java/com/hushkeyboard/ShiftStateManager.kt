package com.hushkeyboard

enum class ShiftState { OFF, ONE_SHOT, CAPS_LOCK }

// Shift state machine. Pure Kotlin — no Android imports. The full state table and the
// casing rules applied to autocorrect output are covered in ShiftStateManagerTest.
class ShiftStateManager {

    var state: ShiftState = ShiftState.OFF
        private set

    val isUppercase: Boolean get() = state != ShiftState.OFF

    fun onShiftActivate(capsLock: Boolean) {
        state = when {
            capsLock -> ShiftState.CAPS_LOCK
            state == ShiftState.OFF -> ShiftState.ONE_SHOT
            state == ShiftState.ONE_SHOT -> ShiftState.OFF
            state == ShiftState.CAPS_LOCK -> ShiftState.OFF
            else -> ShiftState.OFF
        }
    }

    fun onLetterTyped(): Boolean {
        val uppercase = isUppercase
        if (state == ShiftState.ONE_SHOT) {
            state = ShiftState.OFF
        }
        return uppercase
    }

    fun onFieldChange() {
        state = ShiftState.OFF
    }

    // Auto-capitalization. `capsMode` is the value the Android framework returns from
    // InputConnection.getCursorCapsMode(inputType): a set of the CAP_* flag bits below,
    // already resolved against the surrounding text (the framework, not us, decides whether
    // the cursor sits at a sentence/word start). We only translate that answer into a shift
    // state. We deliberately do NOT override a shift the user set by hand: if the current
    // state is anything other than OFF, we leave it alone, so auto-caps can never fight the
    // user's own Shift / caps-lock choice.
    fun applyAutoCaps(capsMode: Int) {
        if (state != ShiftState.OFF) return
        state = when {
            capsMode and CAP_CHARACTERS != 0 -> ShiftState.CAPS_LOCK
            capsMode and (CAP_WORDS or CAP_SENTENCES) != 0 -> ShiftState.ONE_SHOT
            else -> ShiftState.OFF
        }
    }

    companion object {
        // Mirror of android.text.InputType.TYPE_TEXT_FLAG_CAP_* constants, duplicated here so
        // this class stays Android-free and unit-testable on the JVM. getCursorCapsMode returns
        // these same bits set when capitalization should apply at the cursor.
        const val CAP_CHARACTERS = 0x00001000
        const val CAP_WORDS = 0x00002000
        const val CAP_SENTENCES = 0x00004000
    }
}
