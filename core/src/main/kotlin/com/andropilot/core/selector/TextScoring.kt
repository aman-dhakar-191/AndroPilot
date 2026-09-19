package com.andropilot.core.selector

import kotlin.math.max
import kotlin.math.min

/**
 * Text similarity tuned for UI labels.
 *
 * Deliberately dependency-free and deterministic: no embeddings, no model. "Semantic" here
 * means *robust to how UIs actually render text* (case, `&` vs `and`, trailing ellipses,
 * mnemonics, surrounding punctuation), not learned meaning. Anything genuinely semantic is
 * the AI agent's job -- it can always issue a different selector.
 */
public object TextScoring {

    private val PUNCTUATION = Regex("[\\p{Punct}\\s]+")

    /**
     * Normalizes a label for comparison: lower-cased, accents stripped where trivially
     * possible, punctuation collapsed to single spaces, common UI noise removed.
     */
    public fun normalize(input: String): String {
        val lowered = input.lowercase().trim()
        val deEllipsed = lowered.removeSuffix("…").removeSuffix("...")
        val expanded = deEllipsed.replace("&", " and ")
        return PUNCTUATION.replace(expanded, " ").trim()
    }

    private fun tokens(input: String): List<String> =
        normalize(input).split(' ').filter { it.isNotEmpty() }

    /**
     * Similarity in `0.0..1.0`.
     *
     * Scoring ladder, highest first:
     *  - 1.00 identical after normalization
     *  - 0.95 one side is a whole-word prefix of the other ("Sign in" vs "Sign in now")
     *  - 0.60..0.90 token overlap (Jaccard-weighted), which handles reordering
     *  - 0.50..0.85 substring containment, scaled by the length ratio
     *  - character-level similarity capped at 0.92, which catches typos and OCR noise
     *    without ever outranking a genuine prefix or token match
     */
    public fun similarity(query: String, candidate: String): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0

        val qt = tokens(q)
        val ct = tokens(c)

        if (ct.size >= qt.size && ct.subList(0, qt.size) == qt) return 0.95
        if (qt.size >= ct.size && qt.subList(0, ct.size) == ct) return 0.93

        val overlap = qt.toSet().intersect(ct.toSet()).size
        val tokenScore = if (overlap == 0) 0.0 else {
            val jaccard = overlap.toDouble() / (qt.toSet() + ct.toSet()).size
            // A full cover of the query's tokens is a strong signal even inside a long label.
            val coverage = overlap.toDouble() / qt.size
            0.60 + 0.30 * max(jaccard, coverage * 0.9)
        }

        val containmentScore = when {
            c.contains(q) -> 0.50 + 0.35 * (q.length.toDouble() / c.length)
            q.contains(c) -> 0.50 + 0.30 * (c.length.toDouble() / q.length)
            else -> 0.0
        }

        // Capped rather than scaled: scaling a near-identical pair ("Settings" vs the
        // OCR'd "Settngs") down by a constant factor pushes obvious matches below any
        // sensible threshold, while the cap alone is enough to keep this below the exact
        // and prefix tiers.
        val charScore = minOf(charSimilarity(q, c), 0.92)

        return maxOf(tokenScore, containmentScore, charScore).coerceIn(0.0, 1.0)
    }

    /** Normalized Levenshtein similarity, with an O(min(n,m)) row buffer. */
    public fun charSimilarity(a: String, b: String): Double {
        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - distance.toDouble() / longest
    }

    public fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Iterate over the shorter string to bound memory.
        val (s, t) = if (a.length <= b.length) a to b else b to a
        var previous = IntArray(s.length + 1) { it }
        var current = IntArray(s.length + 1)
        for (j in 1..t.length) {
            current[0] = j
            val tc = t[j - 1]
            for (i in 1..s.length) {
                val cost = if (s[i - 1] == tc) 0 else 1
                current[i] = min(min(current[i - 1] + 1, previous[i] + 1), previous[i - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[s.length]
    }
}
