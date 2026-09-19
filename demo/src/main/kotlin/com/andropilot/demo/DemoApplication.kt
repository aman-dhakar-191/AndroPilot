package com.andropilot.demo

import android.app.Application
import com.andropilot.android.AndroPilot
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.observe.TraceRecorder
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.session.SessionConfig
import java.io.File

/**
 * Shows the whole integration surface: one call, with an explicit configuration.
 *
 * The demo uses a *stricter* policy than the SDK default -- confirmation from
 * [RiskLevel.MUTATING] upwards -- so the confirmation flow is visible while exploring
 * rather than only appearing on genuinely destructive taps.
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
                policy = DefaultSafetyPolicy(confirmAtOrAbove = RiskLevel.MUTATING),
                logLevel = LogLevel.DEBUG,
                // This is a debugging tool on a device the developer controls, so the demo
                // opts into screen text. A shipping app should leave both of these off:
                // together they put whatever is on screen into a file and into Logcat.
                allowTextInLogs = true,
                recorder = TraceRecorder.toFile(
                    file = traceFile!!,
                    options = RecordingOptions(includeText = true),
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
