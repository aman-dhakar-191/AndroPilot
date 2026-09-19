package com.andropilot.core.observe

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.PendingConfirmation
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.safety.ConfirmationOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the SDK did, as it happened.
 *
 * One stream rather than several. Perception, actions, safety decisions and connectivity all
 * arrive here in order, so a consumer sees the session as a single narrative instead of
 * having to correlate a results flow against a snapshot flow against a log.
 *
 * Every event is `@Serializable`, so a listener can write it to disk, print it, or put it on
 * a socket without the SDK needing to know which.
 */
@Serializable
public sealed interface AgentEvent {

    /** `System.currentTimeMillis()` when the event occurred. */
    public val at: Long

    /** An action was accepted and is about to run. */
    @Serializable
    @SerialName("action_started")
    public data class ActionStarted(
        val sequence: Long,
        val action: AgentAction,
        override val at: Long,
    ) : AgentEvent

    /** An action finished, successfully or not. */
    @Serializable
    @SerialName("action_finished")
    public data class ActionFinished(
        val sequence: Long,
        val result: ActionResult,
        override val at: Long,
    ) : AgentEvent

    /** The screen was observed. Fires more often than actions do. */
    @Serializable
    @SerialName("snapshot_captured")
    public data class SnapshotCaptured(
        val snapshot: UiSnapshot,
        override val at: Long,
    ) : AgentEvent

    /** The safety policy paused an action and is waiting on a human. */
    @Serializable
    @SerialName("confirmation_required")
    public data class ConfirmationRequired(
        val confirmation: PendingConfirmation,
        override val at: Long,
    ) : AgentEvent

    /** A human answered. */
    @Serializable
    @SerialName("confirmation_resolved")
    public data class ConfirmationResolved(
        val id: String,
        val outcome: ConfirmationOutcome,
        override val at: Long,
    ) : AgentEvent

    /** A free-form marker a developer can drop into the stream to label a run. */
    @Serializable
    @SerialName("note")
    public data class Note(
        val message: String,
        val data: Map<String, String> = emptyMap(),
        override val at: Long,
    ) : AgentEvent

    /** A short line suitable for a live log. */
    public fun summarize(): String = when (this) {
        is ActionStarted -> "#$sequence -> ${action.name}"
        is ActionFinished -> "#$sequence " + when (val r = result) {
            is ActionResult.Success -> buildString {
                append("OK ").append(r.action.name)
                append(" (").append(r.durationMs).append("ms")
                append(", ").append(r.interactionMode.name.lowercase()).append(')')
                r.diff?.let { append(' ').append(if (it.changed) "[changed]" else "[no change]") }
            }
            is ActionResult.Failure ->
                "FAIL ${r.action.name} [${r.reason.name.lowercase()}] ${r.message}"
        }
        is SnapshotCaptured ->
            "observed ${snapshot.elements.size} elements in ${snapshot.packageName ?: "unknown"}"
        is ConfirmationRequired ->
            "confirmation required: ${confirmation.description} (id=${confirmation.id})"
        is ConfirmationResolved -> "confirmation $id ${outcome.name.lowercase()}"
        is Note -> "note: $message"
    }
}

/**
 * Receives [AgentEvent]s as they happen.
 *
 * Listeners registered through [com.andropilot.core.session.SessionConfig.listeners] are
 * called **synchronously**, in order, at the moment the event occurs. That is deliberate: a
 * recorder or a live inspector must not miss events, and a `SharedFlow` subscriber can, both
 * by subscribing late and by falling behind.
 *
 * The cost of that guarantee is that a slow listener slows the session down, and a throwing
 * one would break it -- so the session isolates failures and a listener should do its work
 * quickly or hand it to its own queue.
 */
public fun interface AgentEventListener {
    public fun onEvent(event: AgentEvent)
}
