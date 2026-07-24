package com.hushkeyboard

import kotlin.math.min

// Phase 5 Session 78 — typo-likelihood (edit-cost) prior. Pure Kotlin, no Android, no model, no
// new dependency. It answers the question Session 75 said the ed-2 neural autocorrect was missing:
// "does what they typed even LOOK like a typo of this candidate?" A cheap, common slip — a doubled
// or missing letter, a transposition, an adjacent-key fat-finger — costs little; an arbitrary
// far-key substitution costs a lot.
//
// ContextRescorer.decideCorrection subtracts this cost (in nats) from each candidate's model score,
// so a far-fetched "correction" (yeeted -> seemed: two far-key substitutions) must clear a much
// larger bar before it can override what the user actually typed. The unit is nats so it composes
// directly with the model's length-normalized log-probs and the override margin.
//
// All constants are TARGET starting values, tuned on-device against the Session-77 regression
// harness (AutocorrectRegressionHarnessTest). The signal that separates the harness's good fixes
// from its over-corrections is dominated by FAR_SUB: every ed-2 over-correction involves a far-key
// substitution; the genuine typos are transpositions / doubled letters / adjacent slips.
object EditCost {

    // Per-operation costs (nats). Cheap = plausible typo; expensive = unlikely slip.
    const val TRANSPOSE = 0.5    // swapped adjacent letters: freind -> friend
    const val DOUBLE_GAP = 0.5   // inserted/deleted a doubled letter: tomorow <-> tomorrow
    const val ADJ_SUB = 0.8      // substituted an adjacent key (fat-finger)
    const val FAR_SUB = 2.2      // substituted a far key (arbitrary change) — the expensive case
    const val GAP = 1.3          // inserted/deleted a non-doubled letter

    // Weighted Optimal-String-Alignment distance between [typed] and [candidate] (the caller passes
    // both lowercased), summing the per-operation costs above. Returns total cost in nats; 0 when the
    // strings are identical. Same OSA recurrence as Autocorrect.osa (match / substitute / insert /
    // delete / transpose) but with variable, typo-aware costs instead of a flat 1 per edit.
    fun cost(typed: String, candidate: String): Double {
        val a = typed
        val b = candidate
        val n = a.length
        val m = b.length
        if (n == 0) return m * GAP
        if (m == 0) return n * GAP
        // dp[i][j] = min cost to align a[0..i) with b[0..j). Full matrix (words are short); the
        // transposition rule reaches back to i-2/j-2, so keep all rows rather than a rolling pair.
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) dp[i][0] = dp[i - 1][0] + gapCost(a, i - 1)
        for (j in 1..m) dp[0][j] = dp[0][j - 1] + gapCost(b, j - 1)
        for (i in 1..n) {
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                var best = if (ca == cb) dp[i - 1][j - 1] else dp[i - 1][j - 1] + subCost(ca, cb)
                best = min(best, dp[i - 1][j] + gapCost(a, i - 1))   // delete a[i-1]
                best = min(best, dp[i][j - 1] + gapCost(b, j - 1))   // insert b[j-1]
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    best = min(best, dp[i - 2][j - 2] + TRANSPOSE)   // swap of adjacent letters
                }
                dp[i][j] = best
            }
        }
        return dp[n][m]
    }

    // Cost of inserting/deleting the char at [idx] in [s]. A doubled letter (equal to its neighbour
    // on either side) is a cheap, common slip; any other insert/delete is a full gap.
    private fun gapCost(s: String, idx: Int): Double {
        val c = s[idx]
        val doubled = (idx > 0 && s[idx - 1] == c) || (idx < s.length - 1 && s[idx + 1] == c)
        return if (doubled) DOUBLE_GAP else GAP
    }

    private fun subCost(x: Char, y: Char): Double =
        if (adjacent(x, y)) ADJ_SUB else FAR_SUB

    private fun adjacent(x: Char, y: Char): Boolean = y in (NEIGHBORS[x] ?: "")

    // QWERTY adjacency, computed once from the three letter rows: two keys are adjacent if they sit
    // next to each other horizontally or in the row above/below (including diagonals). Row stagger is
    // ignored — good enough for a fat-finger prior, and symmetric by construction. Non-letters never
    // appear (candidates are dictionary words; typed is letters-only by the time it reaches here).
    private val NEIGHBORS: Map<Char, String> = buildNeighbors()

    private fun buildNeighbors(): Map<Char, String> {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val out = HashMap<Char, String>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val sb = StringBuilder()
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in rows.indices && nc in rows[nr].indices) sb.append(rows[nr][nc])
                }
                out[ch] = sb.toString()
            }
        }
        return out
    }
}
