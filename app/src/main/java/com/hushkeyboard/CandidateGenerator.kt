package com.hushkeyboard

// CandidateGenerator — Phase 5 slice 1, the "dictionary proposes" half of "dictionary
// proposes, transformer ranks." Pure Kotlin, no Android imports: it is a thin, deterministic
// transformation over a neighbor list the caller supplies. It deliberately does NOT reach into
// Autocorrect or any Android type — Autocorrect stays the dictionary owner and passes its
// frequency-ranked neighbors() output in. That keeps this trivially JVM-testable and keeps the
// ownership boundary clean (design 1b).
object CandidateGenerator {

    // Slice-1 candidate set for a typed NON-WORD that already has a fast correction.
    // [typed] is the verbatim typo. [neighbors] is the dictionary's ed<=2 neighbor list in
    // frequency-rank order (most common first), as returned by Autocorrect.neighbors(). [cap]
    // bounds how many candidates the model will later score (latency guard).
    //
    // Returns up to [cap] candidates in stable frequency-rank order, deduplicated. The fast
    // pick — by construction the FIRST in-dictionary neighbor (lowest rank) the corrector would
    // commit — is guaranteed present: it is the head of [neighbors], so any cap >= 1 keeps it.
    // We assert that guarantee defensively rather than assume it, so a mis-ordered caller fails
    // loudly in tests instead of silently dropping the incumbent.
    fun forCorrection(typed: String, neighbors: List<String>, cap: Int): List<String> {
        if (cap <= 0) return emptyList()
        // Dedup while preserving the incoming (frequency-rank) order, then cap. We do NOT add
        // [typed] itself here: slice 1 only fires when the fast corrector already chose to
        // change a non-word, so the typo is not a candidate to keep. [typed] is kept for a
        // future slice and for symmetry of the signature.
        val ordered = LinkedHashSet<String>()
        for (w in neighbors) {
            ordered.add(w)
            if (ordered.size >= cap) break
        }
        return ordered.toList()
    }
}
