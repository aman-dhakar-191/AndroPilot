package com.andropilot.demo.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives the outcome of a [PackageInstaller] session.
 *
 * The important case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system hands back
 * an intent that shows its own install confirmation, and the user decides. Launching that is
 * the whole of this receiver's job -- it is the point at which a human approves, and nothing
 * in the app may stand in for them.
 */
public class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.e(TAG, "Could not show the install prompt.", it) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The new APK is live; the copy that produced it is now dead weight.
                runCatching { AppUpdater(context.applicationContext).cleanUpDownloads() }
                toast(context, "AndroPilot Inspector updated.")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> toast(
                context,
                "Install refused: the signature does not match the installed app. " +
                    "Uninstall it first.",
            )

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Log.i(TAG, "The user cancelled the install.")

            else -> {
                Log.w(TAG, "Install failed with status $status: $message")
                toast(context, "Install failed: ${message ?: "status $status"}")
            }
        }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    public companion object {
        public const val ACTION_INSTALL_STATUS: String =
            "com.andropilot.demo.action.INSTALL_STATUS"
        public const val EXTRA_SESSION_ID: String = "session_id"
        private const val TAG = "AndroPilot-update"
    }
}
