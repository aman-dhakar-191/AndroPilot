package com.andropilot.core.action

import com.andropilot.core.model.Point
import com.andropilot.core.selector.Selector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Direction for scroll and swipe gestures, in terms of *content* movement intent. */
@Serializable
public enum class Direction {
    /** Reveal content further down the list. */
    @SerialName("down") DOWN,

    /** Reveal content further up the list. */
    @SerialName("up") UP,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

/** Hardware / system keys the SDK is willing to synthesize. */
@Serializable
public enum class SystemKey {
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("recents") RECENTS,
    @SerialName("notifications") NOTIFICATIONS,
    @SerialName("quick_settings") QUICK_SETTINGS,
}

/** A condition the SDK can poll for. */
@Serializable
public sealed interface UiCondition {

    @Serializable
    @SerialName("element_present")
    public data class ElementPresent(val selector: Selector) : UiCondition

    @Serializable
    @SerialName("element_absent")
    public data class ElementAbsent(val selector: Selector) : UiCondition

    @Serializable
    @SerialName("package_in_foreground")
    public data class PackageInForeground(@SerialName("package_name") val packageName: String) : UiCondition

    @Serializable
    @SerialName("text_visible")
    public data class TextVisible(val text: String) : UiCondition

    @Serializable
    @SerialName("ui_settled")
    public data class UiSettled(
        /** How long the structural signature must hold steady. */
        @SerialName("stable_for_ms") val stableForMs: Long = 400,
    ) : UiCondition

    @Serializable
    @SerialName("all_of")
    public data class AllOf(val conditions: List<UiCondition>) : UiCondition

    @Serializable
    @SerialName("any_of")
    public data class AnyOf(val conditions: List<UiCondition>) : UiCondition
}

/**
 * Everything an external agent can ask the device to do.
 *
 * A sealed, serializable hierarchy rather than a string-keyed map: it gives Kotlin callers
 * exhaustive `when`s and compile-time safety, while
 * [com.andropilot.core.session.ToolCodec] projects it to/from provider-neutral JSON for
 * remote agents. No LLM tool-calling schema is baked in here.
 */
@Serializable
public sealed interface AgentAction {

    /** Human-readable name used in logs, traces and tool schemas. */
    public val name: String

    // ---- Perception -------------------------------------------------------------------

    @Serializable
    @SerialName("observe")
    public data class Observe(
        /** Ask enabled vision providers to contribute; costs a screenshot. */
        @SerialName("include_visual") val includeVisual: Boolean = false,
        /** Wait for the UI to stop changing before capturing. */
        @SerialName("wait_for_settle") val waitForSettle: Boolean = true,
    ) : AgentAction {
        override val name: String get() = "observe"
    }

    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(
        /** Longest edge in pixels; the image is downscaled to fit. 0 keeps native size. */
        @SerialName("max_dimension") val maxDimension: Int = 1280,
    ) : AgentAction {
        override val name: String get() = "screenshot"
    }

    @Serializable
    @SerialName("find_element")
    public data class FindElement(val selector: Selector) : AgentAction {
        override val name: String get() = "find_element"
    }

    @Serializable
    @SerialName("element_exists")
    public data class ElementExists(val selector: Selector) : AgentAction {
        override val name: String get() = "element_exists"
    }

    // ---- Interaction ------------------------------------------------------------------

    @Serializable
    @SerialName("click")
    public data class Click(
        val selector: Selector,
        /**
         * Fall back to a synthesized tap at the element's centre if the accessibility
         * click action is unavailable or reports failure.
         */
        @SerialName("allow_gesture_fallback") val allowGestureFallback: Boolean = true,
    ) : AgentAction {
        override val name: String get() = "click"
    }

    /**
     * Tap at an absolute screen point. The deliberate escape hatch for apps with no usable
     * accessibility tree -- an agent should reach for [Click] first, and the session logs a
     * warning whenever this is used with a selector-resolvable alternative available.
     */
    @Serializable
    @SerialName("click_point")
    public data class ClickPoint(val point: Point) : AgentAction {
        override val name: String get() = "click_point"
    }

    @Serializable
    @SerialName("long_press")
    public data class LongPress(
        val selector: Selector,
        @SerialName("duration_ms") val durationMs: Long = 600,
    ) : AgentAction {
        override val name: String get() = "long_press"
    }

    @Serializable
    @SerialName("type_text")
    public data class TypeText(
        /** Target field. When null, the currently focused editable element is used. */
        val selector: Selector? = null,
        val text: String,
        /** Replace the field's existing contents instead of appending. */
        val replace: Boolean = true,
        /** Submit the IME action (search/next/done) after typing. */
        @SerialName("press_ime_action") val pressImeAction: Boolean = false,
        /**
         * Marks this text as a credential or one-time code. Redacts it from every log and
         * trace, and raises the action's risk so the host app can require confirmation.
         */
        val sensitive: Boolean = false,
    ) : AgentAction {
        override val name: String get() = "type_text"
    }

    @Serializable
    @SerialName("press_ime_action")
    public data object PressImeAction : AgentAction {
        override val name: String get() = "press_ime_action"
    }

    @Serializable
    @SerialName("clear_text")
    public data class ClearText(val selector: Selector? = null) : AgentAction {
        override val name: String get() = "clear_text"
    }

    @Serializable
    @SerialName("scroll")
    public data class Scroll(
        val direction: Direction = Direction.DOWN,
        /** Container to scroll. When null, the largest visible scrollable is used. */
        val selector: Selector? = null,
        /** Fraction of the container to travel per step, in `0.1..0.9`. */
        val amount: Double = 0.6,
        /** Number of consecutive scroll steps. */
        val steps: Int = 1,
    ) : AgentAction {
        override val name: String get() = "scroll"
    }

    /**
     * Scrolls a container until [until] holds or the content stops moving.
     * The workhorse for "find the item further down the list".
     */
    @Serializable
    @SerialName("scroll_until")
    public data class ScrollUntil(
        val until: UiCondition,
        val direction: Direction = Direction.DOWN,
        val selector: Selector? = null,
        @SerialName("max_steps") val maxSteps: Int = 12,
    ) : AgentAction {
        override val name: String get() = "scroll_until"
    }

    @Serializable
    @SerialName("swipe")
    public data class Swipe(
        val start: Point,
        val end: Point,
        @SerialName("duration_ms") val durationMs: Long = 300,
    ) : AgentAction {
        override val name: String get() = "swipe"
    }

    @Serializable
    @SerialName("press_key")
    public data class PressKey(val key: SystemKey) : AgentAction {
        override val name: String get() = "press_key"
    }

    // ---- App control ------------------------------------------------------------------

    @Serializable
    @SerialName("launch_app")
    public data class LaunchApp(
        @SerialName("package_name") val packageName: String,
        /** Wait until this package owns the foreground window. */
        @SerialName("wait_for_foreground") val waitForForeground: Boolean = true,
    ) : AgentAction {
        override val name: String get() = "launch_app"
    }

    @Serializable
    @SerialName("list_apps")
    public data object ListApps : AgentAction {
        override val name: String get() = "list_apps"
    }

    /**
     * Opens an explicit or implicit intent.
     *
     * Restricted by policy to a host-configured allow-list of actions: an unconstrained
     * intent surface would be a privilege-escalation path out of the SDK's boundaries.
     */
    @Serializable
    @SerialName("open_intent")
    public data class OpenIntent(
        val action: String,
        val uri: String? = null,
        @SerialName("package_name") val packageName: String? = null,
        val extras: Map<String, String> = emptyMap(),
    ) : AgentAction {
        override val name: String get() = "open_intent"
    }

    // ---- Synchronisation --------------------------------------------------------------

    @Serializable
    @SerialName("wait_for")
    public data class WaitFor(
        val condition: UiCondition,
        @SerialName("timeout_ms") val timeoutMs: Long = 5_000,
        @SerialName("poll_interval_ms") val pollIntervalMs: Long = 150,
    ) : AgentAction {
        override val name: String get() = "wait_for"
    }

    /** Unconditional delay. Present because agents sometimes genuinely need one. */
    @Serializable
    @SerialName("sleep")
    public data class Sleep(@SerialName("duration_ms") val durationMs: Long) : AgentAction {
        override val name: String get() = "sleep"
    }

    /**
     * Asserts that a condition already holds. The verification primitive an agent uses to
     * close the loop on a multi-step plan.
     */
    @Serializable
    @SerialName("verify")
    public data class Verify(
        val condition: UiCondition,
        /** Allow the condition a grace period rather than failing instantly. */
        @SerialName("timeout_ms") val timeoutMs: Long = 2_000,
    ) : AgentAction {
        override val name: String get() = "verify"
    }
}
