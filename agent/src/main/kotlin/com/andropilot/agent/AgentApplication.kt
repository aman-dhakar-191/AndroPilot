package com.andropilot.agent

import android.app.Application
import com.andropilot.android.AndroPilot
import com.andropilot.android.LogcatEventListener
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.session.SessionConfig
import com.andropilot.telemetry.TelemetryOptions
import com.andropilot.telemetry.TelemetrySink
import java.io.File
import android.util.Log

/**
 * Wires the SDK up for remote operation.
 *
 * Two decisions are made here and nowhere else, because both have to be true before a host
 * is allowed to say anything:
 *
 * - **The safety policy.** `financialOnly()` asks about money and runs everything else. A
 *   financial action stops and stays pending until somebody picks the phone up; approving
 *   it re-runs the action against whatever is on screen then. The host cannot change this.
 * - **Whether telemetry exists at all.** It is off unless an endpoint has been configured,
 *   and even then it withholds screen text unless that was turned on too.
 */
public class AgentApplication : Application() {

    private val telemetryRelay = object : AgentEventListener {
        @Volatile var sink: TelemetrySink? = null

        override fun onEvent(event: AgentEvent) {
            sink?.onEvent(event)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val settings = AgentSettings(this)

        val listeners = buildList<AgentEventListener> {
            add(LogcatEventListener(format = LogcatEventListener.Format.SUMMARY))
            // The relay, not the link: the socket does not exist yet and may never exist.
            add(AgentController)
            add(telemetryRelay)
        }

        AndroPilot.initialize(
            context = this,
            config = SessionConfig(
                policy = DefaultSafetyPolicy.financialOnly(),
                logLevel = LogLevel.INFO,
                // Off even though the demo turns it on. This app is meant to be left
                // running while somebody else's model drives it, so the screen's contents
                // should not end up in Logcat where any app with the permission can read them.
                allowTextInLogs = false,
                listeners = listeners,
            ),
        )
        reloadTelemetry(this, settings.load())
    }

    /** Re-reads telemetry settings without requiring the Android process to restart. */
    public fun reloadTelemetry(context: android.content.Context, config: AgentConfig) {
        val old = telemetryRelay.sink
        telemetryRelay.sink = null
        runCatching { old?.close() }
        AgentController.reportTelemetryStatus(null)

        if (!config.telemetryEnabled) {
            AgentController.reportTelemetryProblem(null)
            return
        }
        val endpoint = telemetryEndpointFor(config.endpoint)
        if (endpoint == null) {
            AgentController.reportTelemetryProblem(
                "Telemetry is enabled, but the host endpoint is not a valid ws:// or wss:// URL.",
            )
            return
        }
        if (config.token.isBlank()) {
            AgentController.reportTelemetryProblem("Telemetry is enabled, but the shared token is empty.")
            return
        }
        runCatching {
            TelemetrySink.http(
                endpoint = endpoint,
                token = config.token,
                spoolDirectory = File(context.cacheDir, "telemetry"),
                options = TelemetryOptions(
                    deviceId = AgentSettings(context).deviceId(),
                    sdkVersion = BuildConfig.VERSION_NAME,
                    recording = RecordingOptions(
                        includeSnapshots = false,
                        includeText = config.telemetryIncludesText,
                    ),
                ),
            )
        }.onSuccess {
            telemetryRelay.sink = it
            AgentController.reportTelemetryProblem(null)
            AgentController.reportTelemetryStatus("Telemetry active: $endpoint")
            Log.i("AndroPilot-telemetry", "Telemetry active at $endpoint")
        }.onFailure {
            AgentController.reportTelemetryProblem(it.message ?: "The telemetry endpoint was rejected.")
            Log.e("AndroPilot-telemetry", "Could not start telemetry at $endpoint", it)
        }
    }
}
