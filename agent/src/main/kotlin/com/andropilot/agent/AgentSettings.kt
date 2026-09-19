package com.andropilot.agent

import android.content.Context
import java.util.UUID

/** Everything the agent needs to reach a host, and nothing about which model runs there. */
public data class AgentConfig(
    /** For example `wss://desk.local:8765/agent`. Never a build constant. */
    val endpoint: String = "",
    val token: String = "",
    /** A name the human chose, so two phones are distinguishable in the host's log. */
    val deviceName: String = android.os.Build.MODEL ?: "android",
    /** Empty disables telemetry entirely, which is the default. */
    val telemetryEndpoint: String = "",
    /** Ships on-screen text to the telemetry server. Off, and a deliberate choice to turn on. */
    val telemetryIncludesText: Boolean = false,
) {
    val isConfigured: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()
}

/**
 * Stores the agent's configuration.
 *
 * `SharedPreferences` rather than DataStore: this is a handful of strings read once at
 * connect time, and the dependency would buy nothing.
 *
 * The endpoint is configuration, never a constant, because the entire point of this app is
 * that the model runs wherever its owner put it -- a desktop on the LAN today, a VPS
 * tomorrow. Anything compiled in would make that a rebuild.
 */
public class AgentSettings(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("andropilot-agent", Context.MODE_PRIVATE)

    public fun load(): AgentConfig = AgentConfig(
        endpoint = preferences.getString(KEY_ENDPOINT, "") ?: "",
        token = preferences.getString(KEY_TOKEN, "") ?: "",
        deviceName = preferences.getString(KEY_DEVICE, null) ?: (android.os.Build.MODEL ?: "android"),
        telemetryEndpoint = preferences.getString(KEY_TELEMETRY, "") ?: "",
        telemetryIncludesText = preferences.getBoolean(KEY_TELEMETRY_TEXT, false),
    )

    public fun save(config: AgentConfig) {
        preferences.edit()
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_DEVICE, config.deviceName.trim())
            .putString(KEY_TELEMETRY, config.telemetryEndpoint.trim())
            .putBoolean(KEY_TELEMETRY_TEXT, config.telemetryIncludesText)
            .apply()
    }

    /**
     * A random identifier for this installation.
     *
     * Generated once and kept, rather than derived from ANDROID_ID or anything else the
     * hardware knows about itself. Telemetry needs to tell two devices apart; it has no
     * business being able to recognise a particular handset.
     */
    public fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device_name"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TELEMETRY = "telemetry_endpoint"
        const val KEY_TELEMETRY_TEXT = "telemetry_text"
    }
}
