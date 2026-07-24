package com.hushkeyboard

enum class SymbolState { LETTERS, SYMBOLS_1, SYMBOLS_2 }

// Symbol-layer state machine. Pure Kotlin — no Android imports. The full transition table
// and the page-navigation no-op behaviour are covered in SymbolStateManagerTest.
class SymbolStateManager {

    var state: SymbolState = SymbolState.LETTERS
        private set

    val isShowingLetters: Boolean get() = state == SymbolState.LETTERS

    fun onSymbolsKeyTapped() {
        state = SymbolState.SYMBOLS_1
    }

    fun onLettersKeyTapped() {
        state = SymbolState.LETTERS
    }

    fun onPageTwoTapped() {
        if (state == SymbolState.SYMBOLS_1) {
            state = SymbolState.SYMBOLS_2
        }
    }

    fun onPageOneTapped() {
        if (state == SymbolState.SYMBOLS_2) {
            state = SymbolState.SYMBOLS_1
        }
    }

    fun onFieldChange() {
        state = SymbolState.LETTERS
    }
}
