package com.hushkeyboard

// Word buffer and correction-suppression state. Pure Kotlin — no Android imports.
// The suppression flag prevents autocorrect firing on a partial or deliberately
// interrupted word; these invariants are exercised in InputStateManagerTest.
class InputStateManager {

    private val currentWord = StringBuilder()
    private var correctionSuppressed = false

    val isWordEmpty: Boolean get() = currentWord.isEmpty()

    // Read the current buffer without consuming it. Used by the suggestion strip.
    val peekCurrentWord: String get() = currentWord.toString()

    fun onLetterTyped(char: Char, isSensitive: Boolean) {
        if (!isSensitive) {
            currentWord.append(char)
            correctionSuppressed = false
        }
    }

    // Sets the suppression flag so a partial word is not re-expanded by the next commit.
    fun onBackspace(isSensitive: Boolean) {
        if (!isSensitive && currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
            correctionSuppressed = true
        }
    }

    // Returns the typed word for autocorrect, or null if sensitive / suppressed / empty.
    // Always clears the buffer and resets suppression — the word boundary has passed.
    fun onWordCommit(isSensitive: Boolean): String? {
        if (isSensitive || correctionSuppressed || currentWord.isEmpty()) {
            currentWord.clear()
            correctionSuppressed = false
            return null
        }
        val typed = currentWord.toString()
        currentWord.clear()
        correctionSuppressed = false
        return typed
    }

    // The user interrupted a word with a number or symbol; intent is ambiguous, so suppress.
    fun onNonLetterTyped() {
        currentWord.clear()
        correctionSuppressed = true
    }

    fun onFieldChange() {
        currentWord.clear()
        correctionSuppressed = false
    }

    fun onWordDelete() {
        currentWord.clear()
        correctionSuppressed = true
    }
}

// Smart punctuation (Session 43). When the keyboard has just inserted a trailing space — either
// by accepting a predicted word or by autocorrecting on space — and the user then taps a
// sentence/clause mark, the mark should hug the previous word (word? not word ?). The caller
// deletes the pending space, commits the mark, then re-inserts one space. Pure decision only;
// the input-connection edits live in the IME.
object SmartPunctuation {
    // Marks that attach to the preceding word. Decided in Session 43: the six the backlog listed.
    private val ATTACHING_MARKS = setOf('.', ',', '?', '!', ':', ';')

    fun isAttachingMark(c: Char): Boolean = c in ATTACHING_MARKS

    // True only when: a keyboard-inserted space is pending, the char right before the cursor really
    // is that space (live safety check against a stale flag, e.g. after a cursor move), and the
    // tapped key is a single attaching mark.
    fun shouldAttach(pendingAutoSpace: Boolean, charBeforeCursor: Char?, mark: String): Boolean =
        pendingAutoSpace && charBeforeCursor == ' ' && mark.length == 1 && isAttachingMark(mark[0])
}

// Pure word-boundary algorithm. Returns the character count to delete to remove the previous
// word plus the whitespace that precedes it. See InputStateManagerTest for worked examples.
fun countCharsToDeleteForWord(text: CharSequence): Int {
    if (text.isEmpty()) return 0
    var i = text.length
    while (i > 0 && !text[i - 1].isWhitespace()) i--
    while (i > 0 && text[i - 1].isWhitespace()) i--
    return text.length - i
}

// Returns the in-progress word immediately before the cursor (run of non-whitespace chars
// ending at the cursor), or null if there is no word in progress (empty input, or cursor
// is right after whitespace). Case is preserved so callers can read leading-capital state.
// Used by the IME to detect when the user has backspaced into a previously-committed word
// and is now editing it, so the buffer can be re-synchronised with the screen.
fun findWordInProgress(textBefore: CharSequence): String? {
    if (textBefore.isEmpty()) return null
    if (textBefore[textBefore.length - 1].isWhitespace()) return null
    var i = textBefore.length
    while (i > 0 && !textBefore[i - 1].isWhitespace()) i--
    return textBefore.substring(i)
}
