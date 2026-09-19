package com.andropilot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the connection for as long as the user wants it held.
 *
 * The notification is not a formality to satisfy the platform. While this service runs, a
 * process on another machine can read the screen and tap it; the ongoing notification, with
 * a Disconnect action on it, is what keeps that visible and reversible from anywhere in the
 * system. That is why the connection lives here rather than in the activity.
 */
public class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AgentController.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val config = AgentSettings(this).load()
                if (!config.isConfigured) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                createChannel()
                startForeground(NOTIFICATION_ID, notification("Connecting to ${config.endpoint}"))
                AgentController.connect(config, BuildConfig.VERSION_NAME)
                watch()
            }
        }
        // START_STICKY: a connection the user asked for should come back if the process is
        // killed. It still stops the moment they disconnect.
        return START_STICKY
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            AgentController.state.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, notification(describe(state)))
            }
        }
    }

    private fun describe(state: LinkState): String = when (state) {
        is LinkState.Idle -> "Not connected"
        is LinkState.Connecting -> "Connecting to ${state.endpoint}"
        is LinkState.Connected -> "Connected to ${state.endpoint}. This host can control your screen."
        // The reason is shown rather than swallowed: "reconnecting" forever with no cause
        // is the single most common way a tool like this wastes somebody's evening.
        is LinkState.Failed -> "Disconnected: ${state.message}"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AgentActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AndroPilot agent")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a remote host is able to control this device."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        AgentController.disconnect()
        super.onDestroy()
    }

    public companion object {
        public const val ACTION_CONNECT: String = "com.andropilot.agent.CONNECT"
        public const val ACTION_DISCONNECT: String = "com.andropilot.agent.DISCONNECT"
        private const val CHANNEL_ID = "agent-connection"
        private const val NOTIFICATION_ID = 42
    }
}
