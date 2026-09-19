package com.andropilot.core.session

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.UiCondition
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.observe.TraceEntry
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.selector.MatchResult
import com.andropilot.core.selector.Selector
import kotlinx.coroutines.flow.Flow

/**
 * The SDK's public surface: one object that an AI agent runtime -- local or remote -- drives.
 *
 * Every capability is expressible as a single [AgentAction], so a transport can be a thin
 * shim (`execute(json) -> json`) with no per-capability plumbing. The typed helpers below
 * exist purely for Kotlin ergonomics and delegate to [execute].
 *
 * Implementations are safe to call from any coroutine. Actions on a single session are
 * serialized internally: an agent that fires two taps concurrently gets them ordered rather
 * than interleaved, which is the only sane semantics for a shared screen.
 */
public interface AndroPilotSession {

    /** True when the SDK can currently see and drive the device. */
    public val isReady: Boolean

    /** Human-readable reason [isReady] is false, e.g. "accessibility service not enabled". */
    public fun readinessProblem(): String?

    /**
     * Everything the session does, as it happens: actions starting and finishing, screens
     * observed, confirmations raised and resolved.
     *
     * This is the stream to build on. [snapshots] and [results] are filtered views of it,
     * kept for convenience.
     *
     * Like any `SharedFlow`, a subscriber that attaches late or falls behind can miss
     * events. When that is unacceptable -- a recorder, an audit log -- register an
     * [com.andropilot.core.observe.AgentEventListener] through
     * [SessionConfig.listeners] or [addEventListener] instead; those are called
     * synchronously and never dropped.
     */
    public val events: Flow<AgentEvent>

    /** Emits every snapshot the session captures, for inspectors and recorders. */
    public val snapshots: Flow<UiSnapshot>

    /** Emits every action outcome, for traces and remote streaming. */
    public val results: Flow<ActionResult>

    /**
     * Adds a listener for the remainder of the session. Close the returned handle to stop.
     *
     * Prefer [SessionConfig.listeners] when the listener must see the whole session.
     */
    public fun addEventListener(listener: AgentEventListener): AutoCloseable

    /** Puts a marker in the event stream, to label a run while debugging. */
    public fun note(message: String, data: Map<String, String> = emptyMap())

    /** The single entry point. Everything else here is sugar over this. */
    public suspend fun execute(action: AgentAction): ActionResult

    /** Runs actions in order, stopping at the first failure unless [continueOnFailure]. */
    public suspend fun executeAll(
        actions: List<AgentAction>,
        continueOnFailure: Boolean = false,
    ): List<ActionResult>

    /** The most recent snapshot without capturing a new one; null before the first observe. */
    public fun lastSnapshot(): UiSnapshot?

    /** Resolves a selector against the current screen without acting. */
    public suspend fun find(selector: Selector): MatchResult

    /**
     * Answers a pending confirmation, and on approval runs the action it was holding.
     *
     * Returns that action's result, or null if the confirmation was rejected or unknown.
     *
     * The action is re-run rather than resumed, and the difference matters. A human takes
     * seconds to answer, and in that time a list can scroll or a dialog can appear.
     * Re-running re-observes the screen and re-resolves the selector against what is on it
     * now, so an approval cannot be spent on geometry that has since moved -- the same
     * reason the SDK never caches a node across a suspension point. If the screen did
     * change enough that the target is no longer the one described, the returned result is
     * another [com.andropilot.core.action.FailureReason.CONFIRMATION_REQUIRED], which is
     * the honest answer rather than a silent substitution.
     */
    public suspend fun resolveConfirmation(
        id: String,
        outcome: ConfirmationOutcome,
    ): ActionResult?

    /** Confirmations currently awaiting a host decision. */
    public fun pendingConfirmations(): List<com.andropilot.core.action.PendingConfirmation>

    public fun trace(): List<TraceEntry>

    /** Releases resources. The session is unusable afterwards. */
    public suspend fun close()

    // ---- Ergonomic helpers -------------------------------------------------------------

    public suspend fun observe(includeVisual: Boolean = false): ActionResult =
        execute(AgentAction.Observe(includeVisual = includeVisual))

    public suspend fun click(selector: Selector): ActionResult =
        execute(AgentAction.Click(selector))

    public suspend fun click(text: String): ActionResult = click(Selector.text(text))

    public suspend fun longPress(selector: Selector): ActionResult =
        execute(AgentAction.LongPress(selector))

    public suspend fun typeText(
        text: String,
        selector: Selector? = null,
        sensitive: Boolean = false,
    ): ActionResult = execute(AgentAction.TypeText(selector, text, sensitive = sensitive))

    public suspend fun back(): ActionResult =
        execute(AgentAction.PressKey(com.andropilot.core.action.SystemKey.BACK))

    public suspend fun launchApp(packageName: String): ActionResult =
        execute(AgentAction.LaunchApp(packageName))

    public suspend fun waitFor(condition: UiCondition, timeoutMs: Long = 5_000): ActionResult =
        execute(AgentAction.WaitFor(condition, timeoutMs))

    public suspend fun exists(selector: Selector): Boolean {
        val result = execute(AgentAction.ElementExists(selector))
        val data = (result as? ActionResult.Success)?.data
        return (data as? com.andropilot.core.action.ActionData.BooleanValue)?.value == true
    }
}
