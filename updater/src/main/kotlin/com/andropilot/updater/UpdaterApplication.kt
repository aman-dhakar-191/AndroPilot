package com.andropilot.updater

import android.app.Application
import com.andropilot.devtools.update.AppUpdates
import com.andropilot.devtools.update.UpdateTarget

/** The apps this updater looks after, and where their releases come from. */
public class UpdaterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No default repository exists in the library on purpose: one would quietly point
        // at somebody else's releases and fail as "no update available".
        AppUpdates.configure(REPOSITORY)
    }

    public companion object {
        public const val REPOSITORY: String = "aman-dhakar-191/AndroPilot"

        /**
         * Every release carries an APK per app, so each target names the part of the
         * filename that identifies it. Getting this wrong installs the other application,
         * which is exactly what happened while the inspector chose by alphabetical accident.
         */
        public val TARGETS: List<UpdateTarget> = listOf(
            UpdateTarget(
                packageName = "com.andropilot.agent",
                apkAsset = "andropilot-agent",
                label = "AndroPilot Agent",
                disablesAccessibilityService = true,
            ),
            UpdateTarget(
                packageName = "com.andropilot.demo",
                apkAsset = "andropilot-demo",
                label = "AndroPilot Inspector",
                disablesAccessibilityService = true,
            ),
        )
    }
}
