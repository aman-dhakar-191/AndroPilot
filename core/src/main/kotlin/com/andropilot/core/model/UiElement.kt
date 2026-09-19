package com.andropilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A coarse, cross-platform-ish classification of what a UI element *is*, derived from the
 * Android class name plus its capability flags.
 *
 * Agents reason far better over `BUTTON` / `TEXT_FIELD` than over
 * `androidx.appcompat.widget.AppCompatButton`, and roles stay stable when an app swaps
 * widget implementations. The original class name is still available on
 * [UiElement.className] when a consumer needs it.
 */
@Serializable
public enum class ElementRole {
    @SerialName("button") BUTTON,
    @SerialName("text_field") TEXT_FIELD,
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("checkbox") CHECKBOX,
    @SerialName("radio") RADIO,
    @SerialName("switch") SWITCH,
    @SerialName("list") LIST,
    @SerialName("list_item") LIST_ITEM,
    @SerialName("tab") TAB,
    @SerialName("tab_bar") TAB_BAR,
    @SerialName("link") LINK,
    @SerialName("progress") PROGRESS,
    @SerialName("slider") SLIDER,
    @SerialName("dialog") DIALOG,
    @SerialName("web_view") WEB_VIEW,
    @SerialName("scroll_container") SCROLL_CONTAINER,
    @SerialName("container") CONTAINER,
    @SerialName("unknown") UNKNOWN,
    ;

    /** Roles an agent would typically consider "actionable" without further inspection. */
    public val isInteractiveByNature: Boolean
        get() = this in INTERACTIVE

    public companion object {
        private val INTERACTIVE = setOf(
            BUTTON, TEXT_FIELD, CHECKBOX, RADIO, SWITCH, TAB, LINK, SLIDER, LIST_ITEM,
        )
    }
}

/** Where the knowledge about an element came from. */
@Serializable
public enum class PerceptionSource {
    /** Read from the Android accessibility tree. */
    @SerialName("semantic") SEMANTIC,

    /** Produced by a [com.andropilot.core.vision.VisionProvider] from a screenshot. */
    @SerialName("visual") VISUAL,

    /** A semantic node whose text/label was enriched by visual analysis. */
    @SerialName("fused") FUSED,
}

/**
 * Accessibility actions an element advertises. This is deliberately a small, curated set:
 * the full `AccessibilityNodeInfo.AccessibilityAction` list is noisy and mostly irrelevant
 * to an agent. Unrecognised actions are dropped rather than leaked as raw ints.
 */
@Serializable
public enum class UiAction {
    @SerialName("click") CLICK,
    @SerialName("long_click") LONG_CLICK,
    @SerialName("focus") FOCUS,
    @SerialName("set_text") SET_TEXT,
    @SerialName("scroll_forward") SCROLL_FORWARD,
    @SerialName("scroll_backward") SCROLL_BACKWARD,
    @SerialName("expand") EXPAND,
    @SerialName("collapse") COLLAPSE,
    @SerialName("dismiss") DISMISS,
}

/**
 * A single node in the normalized screen representation.
 *
 * Instances are immutable value objects. They intentionally carry **no** reference to any
 * Android object: a [UiElement] can be serialized, sent to a remote agent, stored, and
 * compared across snapshots. Re-locating the live node when an action is dispatched is the
 * driver's job, keyed by [id] within the owning snapshot (see
 * [com.andropilot.core.driver.UiDriver]).
 *
 * @property id Stable within its snapshot and meaningless outside it. Short on purpose: it
 *   appears on every line an agent reads, and an id that encoded the element's position in
 *   the tree cost around a third of the rendered screen. How the driver finds the element
 *   again is [com.andropilot.core.model.UiSnapshot.nodeHandles], which agents never see.
 */
@Serializable
public data class UiElement(
    val id: String,
    val role: ElementRole,
    val bounds: Bounds,
    val text: String? = null,
    @SerialName("content_description") val contentDescription: String? = null,
    /** Hint text of an input field, when the app provides one. */
    val hint: String? = null,
    /** Fully-qualified view id such as `com.example:id/submit`, when present. */
    @SerialName("resource_id") val resourceId: String? = null,
    @SerialName("class_name") val className: String? = null,
    val clickable: Boolean = false,
    @SerialName("long_clickable") val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    /** True when the node is inside the visible window region. */
    val visible: Boolean = true,
    val actions: Set<UiAction> = emptySet(),
    val source: PerceptionSource = PerceptionSource.SEMANTIC,
    /**
     * Confidence in this element's existence and labelling, in `0.0..1.0`.
     * Semantic elements are `1.0`; visual detections carry the provider's score.
     */
    val confidence: Double = 1.0,
    /** [id] of the parent element, or null for the root. */
    @SerialName("parent_id") val parentId: String? = null,
    /** Ids of direct children, in traversal order. */
    @SerialName("child_ids") val childIds: List<String> = emptyList(),
    /** Depth in the tree; the root is 0. */
    val depth: Int = 0,
) {
    /**
     * The best human-meaningful label for this element, preferring what a sighted user
     * would read, then what a screen-reader user would hear.
     */
    public val label: String?
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: hint?.takeIf { it.isNotBlank() }

    /** Can this element be acted on right now? */
    public val isActionable: Boolean
        get() = enabled && visible && !bounds.isEmpty &&
            (clickable || longClickable || editable || scrollable || actions.isNotEmpty())

    /** The point a synthesized tap should target. */
    public val tapPoint: Point get() = bounds.center

    /**
     * A short one-line rendering, used in logs and in the compact snapshot format handed
     * to token-constrained models.
     */
    public fun describe(): String = buildString {
        append(role.name.lowercase())
        label?.let { append(" \"").append(it.take(60)).append('"') }
        resourceId?.let { append(" #").append(it.substringAfterLast('/')) }
        append(' ').append(bounds)
        if (!enabled) append(" disabled")
        if (checkable) append(if (checked) " checked" else " unchecked")
        if (selected) append(" selected")
    }
}
