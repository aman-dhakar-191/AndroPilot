package com.andropilot.demo

import android.app.Application
import com.andropilot.android.AndroPilot
import com.andropilot.android.LogcatEventListener
import com.andropilot.devtools.update.AppUpdates
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.observe.TraceRecorder
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.session.SessionConfig
import java.io.File

/**
 * Shows the whole integration surface: one call, with an explicit configuration.
 *
 * The demo runs unattended, so its policy never asks: a confirmation with nobody there to
 * answer is a block that never clears. It refuses sensitive actions instead, which fails
 * immediately and says why.
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // getExternalFilesDir needs no permission, is wiped when the app is uninstalled, and
        // is reachable with `adb pull`, which makes it the practical home for a trace a
        // developer wants to read on a workstation.
        traceFile = File(getExternalFilesDir(null) ?: filesDir, TRACE_FILE_NAME)

        // Point the updater at this repository's releases. The library has no default: one
        // would silently check someone else's releases and look like "no update available".
        AppUpdates.configure("aman-dhakar-191/AndroPilot")

        AndroPilot.initialize(
            context = this,
            config = SessionConfig(
                // Nobody is standing at the phone to answer a prompt, so the policy never
                // raises one: it runs navigation and ordinary state changes, and refuses
                // anything sensitive outright. Swap in DefaultSafetyPolicy() to exercise
                // the confirmation flow instead, or strict() to confirm every side effect.
                policy = DefaultSafetyPolicy.unattended(),
                logLevel = LogLevel.DEBUG,
                // This is a debugging tool on a device the developer controls, so the demo
                // opts into screen text. A shipping app should leave both of these off:
                // together they put whatever is on screen into a file and into Logcat.
                allowTextInLogs = true,
                // One event stream, two sinks. Logcat is the live view
                // (`adb logcat -s AndroPilot-events`); the file is the durable one.
                listeners = listOf(
                    LogcatEventListener(format = LogcatEventListener.Format.SUMMARY),
                    TraceRecorder.toFile(
                        file = traceFile!!,
                        options = RecordingOptions(includeText = true),
                    ),
                ),
            ),
        )
    }

    companion object {
        const val TRACE_FILE_NAME: String = "andropilot-trace.jsonl"

        /** Where this run is recording, for the inspector UI to display and share. */
        @Volatile
        var traceFile: File? = null
            private set
    }
}
