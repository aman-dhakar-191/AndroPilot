package com.andropilot.demo

import android.app.Application
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.session.SessionConfig
import com.andropilot.android.AndroPilot

/**
 * Shows the whole integration surface: one call, with an explicit configuration.
 *
 * The demo intentionally uses a *stricter* policy than the SDK default -- confirmation from
 * [RiskLevel.MUTATING] upwards -- so the confirmation flow is visible while exploring
 * rather than only appearing on genuinely destructive taps.
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroPilot.initialize(
            context = this,
            config = SessionConfig(
                policy = DefaultSafetyPolicy(confirmAtOrAbove = RiskLevel.MUTATING),
                logLevel = LogLevel.DEBUG,
                // Screen text is shown in the inspector, so redaction is relaxed here.
                // A production integration should leave this false.
                allowTextInLogs = true,
            ),
        )
    }
}
