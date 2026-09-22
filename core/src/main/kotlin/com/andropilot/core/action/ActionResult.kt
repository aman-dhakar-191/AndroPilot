package com.andropilot.core.action

import com.andropilot.core.model.UiDiff
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.selector.MatchCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why an action did not achieve what was asked.
 *
 * Exhaustive and machine-readable: an agent runtime should be able to branch on the reason
 * ("scroll and retry" for [ELEMENT_NOT_FOUND], "ask the user" for [CONFIRMATION_REQUIRED])
 * without parsing an English message.
 */
@Serializable
public enum class FailureReason {
    /** No element passed the selector's threshold. */
    @SerialName("element_not_found") ELEMENT_NOT_FOUND,

    /** Several elements matched and the policy refused to guess. */
    @SerialName("ambiguous_target") AMBIGUOUS_TARGET,

    /** The element was found but is disabled, invisible or zero-sized. */
    @SerialName("element_not_actionable") ELEMENT_NOT_ACTIONABLE,

    /** The element vanished or moved between resolution and dispatch. */
    @SerialName("stale_element") STALE_ELEMENT,

    /** The gesture or accessibility action was dispatched but rejected by the system. */
    @SerialName("dispatch_failed") DISPATCH_FAILED,

    /** The action ran but the UI did not change in any way the SDK could observe. */
    @SerialName("no_effect") NO_EFFECT,

    /** A wait or verification expired. */
    @SerialName("timeout") TIMEOUT,

    /** The host app's [com.andropilot.core.safety.SafetyPolicy] blocked the action. */
    @SerialName("blocked_by_policy") BLOCKED_BY_POLICY,

    /** A sensitive action needs an explicit human decision before it can proceed. */
    @SerialName("confirmation_required") CONFIRMATION_REQUIRED,

    /** The accessibility service is not enabled, or a needed permission is missing. */
    @SerialName("permission_required") PERMISSION_REQUIRED,

    /** The SDK is not connected to a device / the service was torn down. */
    @SerialName("not_connected") NOT_CONNECTED,

    /** The requested app is not installed or has no launchable activity. */
    @SerialName("app_unavailable") APP_UNAVAILABLE,

    /** The platform or API level does not support this operation. */
    @SerialName("unsupported") UNSUPPORTED,

    /** The screen exposed no usable semantic information and no vision provider helped. */
    @SerialName("no_perception") NO_PERCEPTION,

    /** Caller supplied something nonsensical (empty selector, out-of-range point). */
    @SerialName("invalid_request") INVALID_REQUEST,

    /** Anything unclassified; [ActionResult.Failure.message] carries the detail. */
    @SerialName("internal_error") INTERNAL_ERROR,
    ;

    /**
     * Whether retrying the same action unchanged could plausibly succeed. Agents use this
     * to decide between "try again" and "change the plan".
     */
    public val isTransient: Boolean
        get() = this == ELEMENT_NOT_FOUND || this == STALE_ELEMENT ||
            this == TIMEOUT || this == NO_EFFECT || this == DISPATCH_FAILED
}

/** How the SDK physically carried the action out. */
@Serializable
public enum class InteractionMode {
    /** Via `AccessibilityNodeInfo.performAction` -- preferred, most reliable. */
    @SerialName("semantic") SEMANTIC,

    /** Via a synthesized gesture at coordinates derived from the accessibility tree. */
    @SerialName("gesture") GESTURE,

    /** Via a synthesized gesture at coordinates derived from visual analysis. */
    @SerialName("visual") VISUAL,

    /** Via a global system action (back, home, recents). */
    @SerialName("system") SYSTEM,

    /** No device interaction occurred (observation, matching, waiting). */
    @SerialName("none") NONE,
}

/**
 * The structured outcome of exactly one [AgentAction].
 *
 * Actions never throw for expected conditions; every foreseeable problem comes back as a
 * [Failure] so an agent loop can reason about it. Exceptions are reserved for programming
 * errors and cancellation.
 */
@Serializable
public sealed interface ActionResult {

    /** The action that produced this result. */
    public val action: AgentAction

    /** Wall-clock duration of the whole operation, including any waiting. */
    public val durationMs: Long

    public val isSuccess: Boolean get() = this is Success

    @Serializable
    @SerialName("success")
    public data class Success(
        override val action: AgentAction,
        @SerialName("duration_ms") override val durationMs: Long,
        @SerialName("interaction_mode") val interactionMode: InteractionMode = InteractionMode.NONE,
        /** The element the action was actually applied to, if any. */
        val target: UiElement? = null,
        /** How the target was chosen, for auditing ambiguous screens. */
        @SerialName("match_reason") val matchReason: String? = null,
        /** Snapshot taken after the action settled, when the action captures one. */
        val snapshot: UiSnapshot? = null,
        /** What changed as a result. `null` when the action does not verify. */
        val diff: UiDiff? = null,
        /** Result payload for query-style actions. */
        val data: ActionData? = null,
        /** Non-fatal notes, e.g. "fell back to a coordinate tap". */
        val warnings: List<String> = emptyList(),
    ) : ActionResult {
        /** Convenience for the very common "did the screen react?" check. */
        val uiChanged: Boolean get() = diff?.changed ?: false
    }

    @Serializable
    @SerialName("failure")
    public data class Failure(
        override val action: AgentAction,
        @SerialName("duration_ms") override val durationMs: Long,
        val reason: FailureReason,
        /** Developer-facing explanation. Never contains values marked sensitive. */
        val message: String,
        /** Candidates the matcher considered, so an agent can pick or refine. */
        val candidates: List<MatchCandidate> = emptyList(),
        /** Snapshot at the time of failure, to let the agent re-plan without a round trip. */
        val snapshot: UiSnapshot? = null,
        /** Present when [reason] is [FailureReason.CONFIRMATION_REQUIRED]. */
        @SerialName("pending_confirmation") val pendingConfirmation: PendingConfirmation? = null,
        /** A concrete next step the SDK believes would help. */
        val recommendation: String? = null,
    ) : ActionResult {
        val isTransient: Boolean get() = reason.isTransient
    }
}

/** Query payloads carried by [ActionResult.Success.data]. */
@Serializable
public sealed interface ActionData {

    @Serializable
    @SerialName("apps")
    public data class Apps(val apps: List<AppMetadata>) : ActionData

    @Serializable
    @SerialName("element")
    public data class Element(
        val element: UiElement,
        val score: Double,
        val alternatives: List<MatchCandidate> = emptyList(),
    ) : ActionData

    @Serializable
    @SerialName("boolean")
    public data class BooleanValue(val value: Boolean) : ActionData

    @Serializable
    @SerialName("screenshot")
    public data class ScreenshotData(
        /** PNG bytes, base64 encoded, ready to hand to a vision model. */
        @SerialName("png_base64") val pngBase64: String,
        val width: Int,
        val height: Int,
    ) : ActionData

    @Serializable
    @SerialName("snapshot")
    public data class SnapshotData(val snapshot: UiSnapshot) : ActionData
}

@Serializable
public data class AppMetadata(
    val label: String,
    @SerialName("package_name") val packageName: String,
)

/**
 * A sensitive action the SDK has paused. The host app resolves it by calling
 * [com.andropilot.core.session.AndroPilotSession.resolveConfirmation].
 */
@Serializable
public data class PendingConfirmation(
    val id: String,
    val action: AgentAction,
    val risk: RiskLevel,
    /** Plain-language description to show a human, already redacted. */
    val description: String,
    /** Which policy rules flagged it. */
    val reasons: List<String> = emptyList(),
)
