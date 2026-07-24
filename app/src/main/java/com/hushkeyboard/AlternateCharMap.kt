package com.hushkeyboard

// Pure data object — no Android imports. Input is always normalised to lowercase.
object AlternateCharMap {

    private val map: Map<Char, List<String>> = mapOf(
        'a' to listOf("à", "á", "â", "ä", "æ", "ã", "å"),
        'c' to listOf("ç", "ć", "č"),
        'e' to listOf("è", "é", "ê", "ë"),
        'i' to listOf("ì", "í", "î", "ï"),
        'l' to listOf("ł"),
        'n' to listOf("ñ", "ń"),
        'o' to listOf("ò", "ó", "ô", "ö", "œ", "õ"),
        's' to listOf("ß", "ś", "š"),
        'u' to listOf("ù", "ú", "û", "ü"),
        'y' to listOf("ý", "ÿ"),
        'z' to listOf("ź", "ž", "ż")
    )

    // Returns the alternates list for the given letter (case-insensitive), or null if none.
    fun getAlternates(char: Char): List<String>? = map[char.lowercaseChar()]

    fun hasAlternates(char: Char): Boolean = map.containsKey(char.lowercaseChar())

    // Punctuation alternates shown on long-press of a punctuation key (Session 32). These are the
    // common sentence/clause marks otherwise reachable only via the 123 symbols layer, surfaced
    // behind the period key the way Gboard/iOS do. Unlike letters, punctuation has no case, so the
    // lookup is exact (not lowercased) and the caller renders the chips verbatim. The base key
    // character is NOT included here — the popup machinery adds it as the pre-selected chip, so a
    // short tap / release-without-sliding still types the base mark unchanged.
    private val punctuationMap: Map<Char, List<String>> = mapOf(
        '.' to listOf("?", "!", ",", ":", ";", "-"),
        ',' to listOf("'", "\"", ";", ":")
    )

    // Returns the punctuation alternates for the given key character, or null if none.
    fun getPunctuationAlternates(char: Char): List<String>? = punctuationMap[char]

    fun hasPunctuationAlternates(char: Char): Boolean = punctuationMap.containsKey(char)
}
