package com.andropilot.agent

import android.app.Application
import com.andropilot.android.AndroPilot
import com.andropilot.android.LogcatEventListener
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.session.SessionConfig
import com.andropilot.telemetry.TelemetryOptions
import com.andropilot.telemetry.TelemetrySink
import java.io.File

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

    override fun onCreate() {
        super.onCreate()
        val settings = AgentSettings(this)
        val config = settings.load()

        val listeners = buildList<AgentEventListener> {
            add(LogcatEventListener(format = LogcatEventListener.Format.SUMMARY))
            // The relay, not the link: the socket does not exist yet and may never exist.
            add(AgentController)
            if (config.telemetryEndpoint.isNotBlank() && config.token.isNotBlank()) {
                add(
                    TelemetrySink.http(
                        endpoint = config.telemetryEndpoint,
                        token = config.token,
                        spoolDirectory = File(cacheDir, "telemetry"),
                        options = TelemetryOptions(
                            deviceId = settings.deviceId(),
                            sdkVersion = BuildConfig.VERSION_NAME,
                            recording = RecordingOptions(
                                includeSnapshots = false,
                                includeText = config.telemetryIncludesText,
                            ),
                        ),
                    ),
                )
            }
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
    }
}
