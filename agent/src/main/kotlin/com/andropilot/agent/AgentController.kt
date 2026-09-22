package com.andropilot.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import com.andropilot.android.AndroPilot
import com.andropilot.core.action.PendingConfirmation
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.safety.ConfirmationOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one place that knows whether the agent is connected.
 *
 * A singleton because there is one screen, one accessibility service and one socket; the UI
 * and the foreground service are two views of the same thing rather than two owners of it.
 *
 * It is also the relay for events. `SessionConfig.listeners` is fixed when the app starts,
 * long before an endpoint is known, so the session is given [relay] once and the live link
 * attaches to it later.
 */
public object AgentController : AgentEventListener {

    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    public val state: StateFlow<LinkState> get() = _state.asStateFlow()

    @Volatile
    private var link: AgentLink? = null
    private var mirror: Job? = null

    private val _activity = MutableStateFlow<List<ActivityEntry>>(emptyList())

    private val _working = MutableStateFlow(false)
    public val working: StateFlow<Boolean> get() = _working.asStateFlow()

    private val _workingText = MutableStateFlow("AndroPilot working")
    public val workingText: StateFlow<String> get() = _workingText.asStateFlow()

    private var hostRunActive = false

    /**
     * What the agent has been doing, newest first.
     *
     * Bounded, and held only in memory. This is for glancing at the phone after a run, not
     * a record: the durable one is the event stream the host and any telemetry sink see,
     * and a second store here would be the parallel mechanism the project already removed
     * once.
     */
    public val activity: StateFlow<List<ActivityEntry>> get() = _activity.asStateFlow()

    private val _telemetryProblem = MutableStateFlow<String?>(null)

    /**
     * Why telemetry is not running, when it was configured but refused.
     *
     * Kept rather than thrown. A rejected endpoint is a mistake in a settings field, and
     * failing to start the whole app over one leaves no way to correct it.
     */
    public val telemetryProblem: StateFlow<String?> get() = _telemetryProblem.asStateFlow()

    public fun reportTelemetryProblem(message: String) {
        _telemetryProblem.value = message
    }

    private val _pending = MutableStateFlow<List<PendingConfirmation>>(emptyList())

    /**
     * Actions stopped by the safety policy, waiting for a person.
     *
     * The agent ships `financialOnly()`, which leaves a financial action *pending* rather
     * than refusing it -- and pending confirmations never expire. Without somewhere to
     * answer, that is not a safety gate, it is a permanent stall: the action never runs and
     * nothing on the device ever says why.
     */
    public val pending: StateFlow<List<PendingConfirmation>> get() = _pending.asStateFlow()

    /**
     * Registered in `SessionConfig.listeners`; forwards to the link once one exists.
     *
     * Called synchronously on the action path, so everything here is a list append and a
     * flow write. Anything slower would slow the automation it is describing.
     */
    override fun onEvent(event: AgentEvent) {
        link?.onEvent(event)
        when (event) {
            is AgentEvent.ActionStarted -> {
                _workingText.value = friendlyAction(event.action.name)
                _working.value = true
            }
            is AgentEvent.ActionFinished -> if (!hostRunActive) _working.value = false
            is AgentEvent.Note -> when (event.data["kind"]) {
                "intent" -> {
                    hostRunActive = true
                    _workingText.value = "Thinking about the next step"
                    _working.value = true
                }
                "conclusion" -> {
                    hostRunActive = false
                    _working.value = false
                }
                else -> Unit
            }
            else -> Unit
        }
        ActivityEntry.of(event)?.let { entry ->
            _activity.value = (listOf(entry) + _activity.value).take(MAX_ACTIVITY)
        }
        when (event) {
            is AgentEvent.ConfirmationRequired, is AgentEvent.ConfirmationResolved -> refreshPending()
            else -> Unit
        }
    }

    private fun friendlyAction(name: String): String = when (name) {
        "observe" -> "Checking the screen"
        "screenshot" -> "Looking at the screen"
        "find_element" -> "Finding something on screen"
        "element_exists" -> "Checking whether it is visible"
        "click" -> "Tapping the selected control"
        "click_point" -> "Tapping the screen"
        "long_press" -> "Pressing and holding"
        "type_text" -> "Entering text"
        "press_ime_action" -> "Pressing the keyboard action"
        "clear_text" -> "Clearing text"
        "scroll", "scroll_until" -> "Looking further down"
        "swipe" -> "Swiping the screen"
        "press_key" -> "Opening a system panel"
        "launch_app" -> "Opening an app"
        "open_intent" -> "Opening a system screen"
        "wait_for" -> "Waiting for the screen"
        "sleep" -> "Pausing briefly"
        "verify" -> "Verifying the result"
        else -> "Working on your request"
    }

    /** Answers a pending confirmation. Approving re-runs the action against the live screen. */
    public suspend fun resolve(id: String, outcome: ConfirmationOutcome) {
        runCatching { AndroPilot.session().resolveConfirmation(id, outcome) }
        refreshPending()
    }

    public fun refreshPending() {
        _pending.value = runCatching { AndroPilot.session().pendingConfirmations() }.getOrDefault(emptyList())
    }

    /** Connects, or reconnects with a new configuration. */
    public fun connect(config: AgentConfig, sdkVersion: String) {
        disconnect()
        val created = AgentLink(scope, AndroPilot.session(), config, sdkVersion)
        link = created
        mirror = scope.launch { created.state.collect { _state.value = it } }
        created.start()
    }

    public fun disconnect() {
        mirror?.cancel()
        mirror = null
        link?.stop()
        link = null
        _state.value = LinkState.Idle
        hostRunActive = false
        _working.value = false
        _workingText.value = "AndroPilot working"
    }

    /**
     * Starts the foreground service, which is what actually holds the connection.
     *
     * Routed through a service rather than started from the activity because the connection
     * must outlive the screen -- and because a foreground service cannot run without an
     * ongoing notification, which is the only signal the phone's owner has that something
     * remote can drive their device.
     */
    public fun requestConnect(context: Context) {
        val intent = Intent(context, AgentService::class.java).setAction(AgentService.ACTION_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    public fun requestDisconnect(context: Context) {
        context.startService(Intent(context, AgentService::class.java).setAction(AgentService.ACTION_DISCONNECT))
    }

    /** Enough of a run to review it afterwards, and no more. */
    private const val MAX_ACTIVITY = 60
}

/** One line of the phone's own account of what happened. */
public data class ActivityEntry(
    val at: Long,
    val kind: Kind,
    val text: String,
) {
    public enum class Kind { INTENT, ACTION, FAILURE, CONFIRMATION, NOTE }

    public companion object {
        /**
         * Turns an event into a line, or nothing.
         *
         * Snapshots and action starts are dropped: a snapshot is captured several times per
         * action by the settle loop, and a start says nothing a finish does not.
         *
         * Screen text reaches this list even though the app sets `allowTextInLogs = false`.
         * That flag governs what leaves the device through a log or a sink. This is the
         * phone's own screen, shown to the person holding it -- withholding what an action
         * targeted would make the list useless for the one purpose it has.
         */
        public fun of(event: AgentEvent): ActivityEntry? = when (event) {
            is AgentEvent.Note -> ActivityEntry(
                at = event.at,
                kind = if (event.data["kind"] == "intent") Kind.INTENT else Kind.NOTE,
                text = event.message,
            )
            is AgentEvent.ActionFinished -> ActivityEntry(
                at = event.at,
                kind = if (event.result.isSuccess) Kind.ACTION else Kind.FAILURE,
                text = event.summarize().substringAfter(' '),
            )
            is AgentEvent.ConfirmationRequired -> ActivityEntry(
                at = event.at,
                kind = Kind.CONFIRMATION,
                text = "Waiting for you: ${event.confirmation.description}",
            )
            is AgentEvent.ConfirmationResolved -> ActivityEntry(
                at = event.at,
                kind = Kind.CONFIRMATION,
                text = "You ${event.outcome.name.lowercase()} a confirmation.",
            )
            is AgentEvent.ActionStarted, is AgentEvent.SnapshotCaptured -> null
        }
    }
}
