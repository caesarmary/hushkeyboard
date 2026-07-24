package com.hushkeyboard

// Pure Kotlin: decides which lines of a bundled legal .txt asset are section headers
// (written as plain ALL-CAPS lines, e.g. "WHAT WE COLLECT") so LegalActivity can give
// them real visual hierarchy instead of rendering everything as flat body text.
object LegalTextFormatter {

    fun isHeaderLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        if (trimmed.none { it.isLetter() }) return false
        return trimmed.none { it.isLowerCase() }
    }
}
