package com.andropilot.core.selector

import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a text criterion should be compared.
 *
 * [SEMANTIC] is the default an agent should reach for: it tolerates case, punctuation,
 * whitespace and minor wording differences, which is what actually happens when a model
 * writes "Sign in" and the app renders "SIGN IN".
 */
@Serializable
public enum class TextMatch {
    @SerialName("exact") EXACT,
    @SerialName("ignore_case") IGNORE_CASE,
    @SerialName("contains") CONTAINS,
    @SerialName("starts_with") STARTS_WITH,
    @SerialName("regex") REGEX,

    /** Normalized + fuzzy; see [com.andropilot.core.selector.TextScoring]. */
    @SerialName("semantic") SEMANTIC,
}

/** Coarse screen region, for disambiguating duplicates without hard-coding pixels. */
@Serializable
public enum class ScreenRegion {
    @SerialName("top") TOP,
    @SerialName("bottom") BOTTOM,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("center") CENTER,
    ;

    internal fun contains(bounds: Bounds, screen: Bounds): Boolean {
        val cx = bounds.centerX
        val cy = bounds.centerY
        return when (this) {
            TOP -> cy < screen.height / 3
            BOTTOM -> cy > screen.height * 2 / 3
            LEFT -> cx < screen.width / 3
            RIGHT -> cx > screen.width * 2 / 3
            CENTER -> cx in (screen.width / 4)..(screen.width * 3 / 4) &&
                cy in (screen.height / 4)..(screen.height * 3 / 4)
        }
    }
}

/** What to do when more than one element matches equally well. */
@Serializable
public enum class AmbiguityPolicy {
    /** Report [com.andropilot.core.action.FailureReason.AMBIGUOUS_TARGET] and act on nothing. */
    @SerialName("fail") FAIL,

    /** Take the highest-scoring candidate; ties broken by reading order (top-left first). */
    @SerialName("first") FIRST,

    /** Take the best candidate only if it clearly beats the runner-up. */
    @SerialName("best_or_fail") BEST_OR_FAIL,
}

/**
 * A declarative description of the element an agent wants to act on.
 *
 * Every field is an optional constraint; they are ANDed together. A selector is a plain
 * serializable value, so an external agent can emit one as JSON and the SDK resolves it
 * against whatever is on screen at that moment. This is the key indirection that keeps the
 * agent away from coordinates and away from stale node handles.
 *
 * ```
 * Selector(text = "Send", role = ElementRole.BUTTON, clickable = true)
 * ```
 */
@Serializable
public data class Selector(
    /** Matched against text, content description and hint. */
    val text: String? = null,
    @SerialName("text_match") val textMatch: TextMatch = TextMatch.SEMANTIC,
    /** Matched only against the visible text. */
    @SerialName("exact_text") val exactText: String? = null,
    /** Matched only against the content description. */
    @SerialName("content_description") val contentDescription: String? = null,
    /**
     * Matched against the resource id. Accepts either the full `pkg:id/name` form or just
     * the `name` suffix, because agents rarely know the package prefix.
     */
    @SerialName("resource_id") val resourceId: String? = null,
    /** Substring match against the Android class name, for advanced callers. */
    @SerialName("class_name") val className: String? = null,
    val role: ElementRole? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checkable: Boolean? = null,
    val checked: Boolean? = null,
    val enabled: Boolean? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    /** Restrict to elements whose centre falls in this region of the screen. */
    val region: ScreenRegion? = null,
    /** Restrict to descendants of the element matched by this selector. */
    val within: @Serializable Selector? = null,
    /** Prefer the candidate nearest to the element matched by this selector. */
    @SerialName("near") val near: @Serializable Selector? = null,
    /** 0-based pick among equally-ranked candidates in reading order. */
    val index: Int? = null,
    /** Only consider elements that are on screen. Defaults to true. */
    @SerialName("visible_only") val visibleOnly: Boolean = true,
    /**
     * Only consider elements the SDK can actually act on. Leave null to let the action
     * decide (a click implies actionable, a read does not).
     */
    val actionable: Boolean? = null,
    @SerialName("on_ambiguity") val onAmbiguity: AmbiguityPolicy = AmbiguityPolicy.BEST_OR_FAIL,
    /**
     * Minimum score in `0.0..1.0` a candidate must reach. Raising this makes matching
     * stricter; lowering it makes the SDK more willing to guess.
     */
    @SerialName("min_score") val minScore: Double = 0.55,
) {
    /** True when the selector places no constraint at all, which is almost never intended. */
    public val isEmpty: Boolean
        get() = text == null && exactText == null && contentDescription == null &&
            resourceId == null && className == null && role == null && within == null &&
            near == null && region == null && clickable == null && editable == null &&
            scrollable == null && checkable == null && checked == null && enabled == null &&
            selected == null && focused == null && index == null

    public fun describe(): String = buildString {
        append("Selector(")
        val parts = buildList {
            text?.let { add("text~\"$it\"") }
            exactText?.let { add("text=\"$it\"") }
            contentDescription?.let { add("desc~\"$it\"") }
            resourceId?.let { add("id=$it") }
            role?.let { add("role=${it.name.lowercase()}") }
            className?.let { add("class~$it") }
            region?.let { add("region=${it.name.lowercase()}") }
            clickable?.let { add("clickable=$it") }
            editable?.let { add("editable=$it") }
            scrollable?.let { add("scrollable=$it") }
            checked?.let { add("checked=$it") }
            enabled?.let { add("enabled=$it") }
            selected?.let { add("selected=$it") }
            index?.let { add("index=$it") }
            within?.let { add("within=${it.describe()}") }
            near?.let { add("near=${it.describe()}") }
        }
        append(parts.joinToString(", "))
        append(')')
    }

    public companion object {
        /** Shorthand for the common "tap the thing that says X" case. */
        public fun text(value: String): Selector = Selector(text = value)

        public fun id(value: String): Selector = Selector(resourceId = value)

        public fun role(value: ElementRole): Selector = Selector(role = value)
    }
}
