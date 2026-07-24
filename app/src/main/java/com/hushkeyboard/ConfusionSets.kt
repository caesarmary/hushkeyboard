package com.hushkeyboard

// ConfusionSets — Phase 5 slice 2, the static candidate source for real-word / contraction homophone
// overrides. Pure Kotlin, no Android imports.
//
// Unlike slice 1b (which corrects NON-words using the dictionary's edit-distance neighbors), slice 2
// acts on CORRECTLY-SPELLED words the dictionary leaves alone but that sentence context shows are
// likely the wrong member of a homophone / contraction family ("from" vs "form", "their" vs "there").
// The candidate set is therefore NOT user content and NOT the learned-words dictionary: it is this
// small hand-curated constant, which keeps it strictly less sensitive than slice 1b's candidates
// (SECURITY.md rule 4 review, Session 73).
//
// Starter families (Session 73 scope; more can be appended later). Contractions keep their
// apostrophe; the keyboard commits the member verbatim.
object ConfusionSets {

    // Each inner list is one confusion family. Order within a family is not significant — the model
    // ranks them, and the typed word is always the incumbent regardless of its position.
    private val FAMILIES: List<List<String>> = listOf(
        listOf("form", "from"),
        listOf("there", "their", "they're"),
        listOf("its", "it's"),
        listOf("your", "you're"),
        listOf("than", "then"),
        listOf("whether", "weather"),
    )

    // word (lowercase) -> its family (including itself). Built once at class load.
    private val BY_WORD: Map<String, List<String>> =
        FAMILIES.flatMap { fam -> fam.map { it to fam } }.toMap()

    // The confusion family for [word] (matched case-insensitively), or null if [word] is in no
    // family. When non-null the list has size >= 2 and contains [word]'s lowercased form.
    fun candidatesFor(word: String): List<String>? = BY_WORD[word.lowercase()]
}
