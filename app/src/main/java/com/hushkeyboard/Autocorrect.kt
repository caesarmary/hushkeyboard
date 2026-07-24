package com.hushkeyboard

import android.content.Context

class Autocorrect {

    private val words = HashSet<String>()
    private val wordsByLength = HashMap<Int, ArrayList<Pair<String, Int>>>()

    constructor(context: Context) {
        context.assets.open("wordlist_en.txt").bufferedReader().useLines { lines ->
            addWords(lines)
        }
    }

    internal constructor(wordSet: Collection<String>) {
        addWords(wordSet.asSequence())
    }

    private fun addWords(sequence: Sequence<String>) {
        var rank = 0
        for (line in sequence) {
            val word = line.trim().split(" ")[0].lowercase()
            if (word.length >= 3 && word.all { it.isLetter() }) {
                if (words.add(word)) {
                    wordsByLength.getOrPut(word.length) { ArrayList() }.add(Pair(word, rank))
                    rank++
                }
            }
        }
    }

    // Whether [word] is already in the static dictionary (case-insensitive). Used by the
    // learned-words capture path (Session 36) so it only stores NOVEL words — names, slang,
    // jargon the dictionary does not know — and does not waste the bounded learned dictionary
    // on common words autocorrect already handles.
    fun isKnownWord(word: String): Boolean = word.lowercase() in words

    // [isLearned] — Session-78 learned-words shield. The fast corrector already leaves words it
    // finds in the static dictionary untouched (the `lower in words` check below); this extends the
    // same "it's already a real word, don't touch it" guard to words the user TAUGHT the keyboard
    // (the encrypted learned-words store), which the static dictionary does not know. Defaults to
    // {false} so the 22 existing AutocorrectTest cases are unchanged; the service passes the live
    // store's lookup. Fixes Session-75: a learned name like "priya" was being clobbered to "prima".
    fun correct(input: String, isLearned: (String) -> Boolean = { false }): String? {
        val lower = input.lowercase()
        if (lower.length < 3) return null
        if (lower in words) return null
        if (isLearned(lower)) return null

        val len = lower.length
        var bestWord: String? = null
        var bestRank = Int.MAX_VALUE

        for (candidateLen in listOf(len, len + 1, len - 1)) {
            val bucket = wordsByLength[candidateLen] ?: continue
            for ((word, rank) in bucket) {
                if (rank >= bestRank) break
                if (osa(lower, word) == 1) {
                    bestRank = rank
                    bestWord = word
                    break
                }
            }
        }

        return bestWord
    }

    // Dictionary words within OSA distance <= [maxEdits] of [input] (lowercased), in
    // frequency-rank order (most common first), deduplicated. This is the Phase 5 candidate
    // source: it generalizes correct()'s search (which is dist-1-only) to dist-2 without
    // changing correct(). Reuses the same wordsByLength buckets; the length window is widened
    // to len-maxEdits..len+maxEdits because an edit can only change the length by one each.
    internal fun neighbors(input: String, maxEdits: Int): List<String> {
        val lower = input.lowercase()
        val len = lower.length
        // Collect (word, rank) within distance, then sort by rank so output is frequency-ranked.
        val found = ArrayList<Pair<String, Int>>()
        for (candidateLen in (len - maxEdits)..(len + maxEdits)) {
            val bucket = wordsByLength[candidateLen] ?: continue
            for ((word, rank) in bucket) {
                if (osa(lower, word, maxEdits) <= maxEdits) {
                    found.add(Pair(word, rank))
                }
            }
        }
        // Sort by rank (most common first); buckets are already rank-ordered within a length,
        // but distance spans multiple buckets, so sort across them. Dedup by word.
        return found.sortedBy { it.second }.map { it.first }.distinct()
    }

    private fun osa(a: String, b: String): Int = osa(a, b, 1)

    // [maxDist] generalizes the early-exit: the loop bails once a whole row's minimum exceeds
    // maxDist (the true distance can only grow from there), returning that minimum as a value
    // that is guaranteed > maxDist. For maxDist = 1 this is byte-for-byte the original behavior,
    // so existing correct() callers are unaffected.
    private fun osa(a: String, b: String, maxDist: Int): Int {
        val lenA = a.length
        val lenB = b.length
        var prev2 = IntArray(lenB + 1)
        var prev  = IntArray(lenB + 1) { it }
        var curr  = IntArray(lenB + 1)

        for (i in 1..lenA) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..lenB) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j], curr[j - 1], prev[j - 1])
                }
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    curr[j] = minOf(curr[j], prev2[j - 2] + 1)
                }
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDist) return rowMin
            val tmp = prev2; prev2 = prev; prev = curr; curr = tmp
        }
        return prev[lenB]
    }
}
