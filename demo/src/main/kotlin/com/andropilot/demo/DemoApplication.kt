package com.andropilot.demo

import android.app.Application
import com.andropilot.android.AndroPilot
import com.andropilot.android.LogcatEventListener
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

        AndroPilot.initialize(
            context = this,
            config = SessionConfig(
                // Asks about money and nothing else. Everything else -- deleting, sending,
                // posting -- runs unsupervised. A financial action stops and stays pending
                // until someone picks the phone up, because pending confirmations do not
                // expire and approving one runs it.
                //
                // unattended() refuses sensitive actions instead of asking; strict()
                // confirms every side effect; permissive() never stops anything.
                policy = DefaultSafetyPolicy.financialOnly(),
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
