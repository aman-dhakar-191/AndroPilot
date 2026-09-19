package com.andropilot.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import com.andropilot.android.AndroPilot
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
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

    /** Registered in `SessionConfig.listeners`; forwards to the link once one exists. */
    override fun onEvent(event: AgentEvent) {
        link?.onEvent(event)
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
}
