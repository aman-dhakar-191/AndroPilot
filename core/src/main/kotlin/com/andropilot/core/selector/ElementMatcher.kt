package com.andropilot.core.selector

import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One candidate produced by [ElementMatcher], with enough detail for an agent to audit. */
@Serializable
public data class MatchCandidate(
    val element: UiElement,
    val score: Double,
    /** Human-readable justification, e.g. `resource id exact; role button`. */
    val reason: String,
)

/** Outcome of resolving a [Selector] against a [UiSnapshot]. */
@Serializable
public sealed interface MatchResult {

    @Serializable
    @SerialName("matched")
    public data class Matched(
        val candidate: MatchCandidate,
        /** Other plausible candidates, best first, for diagnostics. */
        val runnersUp: List<MatchCandidate> = emptyList(),
    ) : MatchResult

    @Serializable
    @SerialName("not_found")
    public data class NotFound(
        /** Near-misses that failed the score threshold, best first. */
        @SerialName("near_misses") val nearMisses: List<MatchCandidate> = emptyList(),
    ) : MatchResult

    @Serializable
    @SerialName("ambiguous")
    public data class Ambiguous(
        val candidates: List<MatchCandidate>,
    ) : MatchResult
}

/**
 * Resolves [Selector]s against a [UiSnapshot].
 *
 * Pure and synchronous by design: matching never touches Android, which is what makes the
 * rules here exhaustively unit-testable against synthetic screens.
 */
public object ElementMatcher {

    /** How much better the winner must be for [AmbiguityPolicy.BEST_OR_FAIL] to accept it. */
    private const val DECISIVE_MARGIN = 0.12

    /** Candidates below this fraction of the best score are not worth reporting. */
    private const val REPORT_FLOOR = 0.6

    /** A score at or above this counts as a perfect match after normalization. */
    private const val PERFECT = 0.999

    public fun find(snapshot: UiSnapshot, selector: Selector): MatchResult {
        if (selector.isEmpty) {
            return MatchResult.NotFound(emptyList())
        }

        val scope = resolveScope(snapshot, selector) ?: return MatchResult.NotFound(emptyList())
        val anchor = selector.near?.let { near ->
            (find(snapshot, near) as? MatchResult.Matched)?.candidate?.element
        }

        val scored = scope.asSequence()
            .mapNotNull { element -> score(snapshot, element, selector, anchor) }
            .sortedWith(
                compareByDescending<MatchCandidate> { it.score }
                    .thenBy { it.element.bounds.top }
                    .thenBy { it.element.bounds.left }
                    .thenBy { it.element.id },
            )
            .toList()

        val passing = scored.filter { it.score >= selector.minScore }

        selector.index?.let { idx ->
            val pick = passing.getOrNull(idx)
                ?: return MatchResult.NotFound(scored.take(5))
            return MatchResult.Matched(pick, passing.filterNot { it === pick }.take(4))
        }

        if (passing.isEmpty()) {
            return MatchResult.NotFound(scored.take(5))
        }

        val best = passing.first()
        val rest = passing.drop(1)
        val runnerUp = rest.firstOrNull()
        // A perfect match is never ambiguous against an imperfect one. Without this rule,
        // sibling list rows that differ by a single character ("Setting option 3" next to
        // "Setting option 1") sit within DECISIVE_MARGIN of an exact hit, because the same
        // character-level tolerance that rescues OCR typos also blurs near-identical
        // labels. Score alone cannot serve both; exactness breaks the tie.
        val decisive = runnerUp == null ||
            (best.score - runnerUp.score) >= DECISIVE_MARGIN ||
            (best.score >= PERFECT && runnerUp.score < PERFECT)

        val reported = rest.filter { it.score >= best.score * REPORT_FLOOR }.take(4)

        return when {
            decisive -> MatchResult.Matched(best, reported)
            selector.onAmbiguity == AmbiguityPolicy.FIRST -> MatchResult.Matched(best, reported)
            selector.onAmbiguity == AmbiguityPolicy.BEST_OR_FAIL ->
                MatchResult.Ambiguous(listOf(best) + reported)
            else -> MatchResult.Ambiguous(listOf(best) + reported)
        }
    }

    /** Convenience: every element passing the selector's threshold, best first. */
    public fun findAll(snapshot: UiSnapshot, selector: Selector): List<MatchCandidate> {
        val scope = resolveScope(snapshot, selector) ?: return emptyList()
        val anchor = selector.near?.let { near ->
            (find(snapshot, near) as? MatchResult.Matched)?.candidate?.element
        }
        return scope.mapNotNull { score(snapshot, it, selector, anchor) }
            .filter { it.score >= selector.minScore }
            .sortedWith(
                compareByDescending<MatchCandidate> { it.score }
                    .thenBy { it.element.bounds.top }
                    .thenBy { it.element.bounds.left },
            )
    }

    private fun resolveScope(snapshot: UiSnapshot, selector: Selector): List<UiElement>? {
        val within = selector.within ?: return snapshot.elements
        val container = (find(snapshot, within) as? MatchResult.Matched)?.candidate?.element
            ?: return null
        return snapshot.descendantsOf(container)
    }

    /**
     * Returns null when a hard constraint is violated, otherwise a scored candidate.
     *
     * Boolean constraints are hard filters: an agent asking for `clickable = true` never
     * wants a non-clickable element, however well the text matches. Text constraints are
     * soft and contribute a score, because that is where real-world drift lives.
     */
    private fun score(
        snapshot: UiSnapshot,
        element: UiElement,
        selector: Selector,
        anchor: UiElement?,
    ): MatchCandidate? {
        if (selector.visibleOnly && !element.visible) return null
        if (selector.actionable == true && !element.isActionable) return null
        selector.clickable?.let { if (element.clickable != it) return null }
        selector.editable?.let { if (element.editable != it) return null }
        selector.scrollable?.let { if (element.scrollable != it) return null }
        selector.checkable?.let { if (element.checkable != it) return null }
        selector.checked?.let { if (element.checked != it) return null }
        selector.enabled?.let { if (element.enabled != it) return null }
        selector.selected?.let { if (element.selected != it) return null }
        selector.focused?.let { if (element.focused != it) return null }
        selector.role?.let { if (element.role != it) return null }
        selector.className?.let {
            if (element.className?.contains(it, ignoreCase = true) != true) return null
        }
        selector.region?.let {
            if (!it.contains(element.bounds, snapshot.metrics.frame)) return null
        }
        selector.exactText?.let { if (element.text != it) return null }
        selector.contentDescription?.let {
            val cd = element.contentDescription ?: return null
            if (TextScoring.similarity(it, cd) < 0.9) return null
        }

        val reasons = ArrayList<String>(4)
        var score = 0.0
        var weight = 0.0

        selector.resourceId?.let { wanted ->
            val actual = element.resourceId ?: return null
            val hit = actual == wanted ||
                actual.substringAfterLast('/') == wanted.substringAfterLast('/')
            if (!hit) return null
            score += 1.0; weight += 1.0
            reasons += "resource id exact"
        }

        selector.text?.let { wanted ->
            val top = candidateStrings(element)
                .map { (kind, value) -> Triple(kind, value, matchText(wanted, value, selector.textMatch)) }
                .maxByOrNull { it.third }
                ?: return null
            if (top.third <= 0.0) return null
            score += top.third
            weight += 1.0
            reasons += "${top.first} \"${top.second.take(40)}\" ~ ${formatScore(top.third)}"
        }

        if (weight == 0.0) {
            // Only structural constraints were given; they all passed.
            score = 1.0
            weight = 1.0
            reasons += "structural match"
        }

        var finalScore = score / weight

        // Soft preferences. These never rescue a failed constraint, they only order ties.
        if (selector.role == null && element.role.isInteractiveByNature) finalScore += 0.02
        // Containers routinely duplicate their child's text, so an actionable element must
        // beat an identically-labelled decorative one *decisively*. This is expressed as a
        // penalty on non-actionable elements rather than a bonus on actionable ones: a bonus
        // is swallowed by the 0..1 clamp exactly when the match is strongest, which is when
        // the separation matters most. Preferring rather than hard-filtering keeps a disabled
        // or non-clickable match available, so the session can explain why it is unusable
        // instead of claiming the element does not exist.
        if (!element.isActionable) finalScore *= 0.8
        // A disabled element is almost never what an agent meant, even when its label is a
        // perfect match, so the penalty has to exceed DECISIVE_MARGIN to actually break a tie.
        if (!element.enabled) finalScore -= 0.25
        finalScore *= element.confidence.coerceIn(0.1, 1.0)

        anchor?.let { a ->
            // `near` is an explicit disambiguation instruction, so proximity carries half
            // the weight. The reference distance is a quarter of the screen width rather
            // than the diagonal: a linear falloff over the diagonal barely separates two
            // candidates on the same screen, which is exactly the case `near` exists for.
            val distance = element.bounds.center.distanceTo(a.bounds.center)
            val reference = (snapshot.metrics.widthPx / 4.0).coerceAtLeast(1.0)
            val proximity = 1.0 / (1.0 + distance / reference)
            finalScore = finalScore * 0.5 + proximity * 0.5
            reasons += "near anchor (${distance.toInt()}px)"
        }

        selector.role?.let { reasons += "role ${it.name.lowercase()}" }

        return MatchCandidate(element, finalScore.coerceIn(0.0, 1.0), reasons.joinToString("; "))
    }

    private fun formatScore(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private fun candidateStrings(element: UiElement): List<Pair<String, String>> = buildList {
        element.text?.takeIf { it.isNotBlank() }?.let { add("text" to it) }
        element.contentDescription?.takeIf { it.isNotBlank() }?.let { add("description" to it) }
        element.hint?.takeIf { it.isNotBlank() }?.let { add("hint" to it) }
        element.resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?.let { add("resource-id" to it.replace('_', ' ')) }
    }

    private fun matchText(query: String, value: String, mode: TextMatch): Double = when (mode) {
        TextMatch.EXACT -> if (value == query) 1.0 else 0.0
        TextMatch.IGNORE_CASE -> if (value.equals(query, ignoreCase = true)) 1.0 else 0.0
        TextMatch.CONTAINS -> if (value.contains(query, ignoreCase = true)) 0.9 else 0.0
        TextMatch.STARTS_WITH -> if (value.startsWith(query, ignoreCase = true)) 0.95 else 0.0
        TextMatch.REGEX -> runCatching { if (Regex(query).containsMatchIn(value)) 0.9 else 0.0 }
            .getOrDefault(0.0)
        TextMatch.SEMANTIC -> TextScoring.similarity(query, value)
    }
}
