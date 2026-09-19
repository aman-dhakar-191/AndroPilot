package com.andropilot.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.andropilot.android.internal.AccessibilityUiDriver
import com.andropilot.core.observe.ActionTrace
import com.andropilot.core.observe.AgentLogger
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.session.AndroPilotSession
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The SDK's entry point on Android.
 *
 * Typical integration is three steps:
 *
 * ```kotlin
 * // 1. Once, in Application.onCreate:
 * AndroPilot.initialize(this)
 *
 * // 2. Ask the user to grant control, if they have not already:
 * if (!AndroPilot.isServiceEnabled(context)) {
 *     AndroPilot.openAccessibilitySettings(context)
 * }
 *
 * // 3. Drive the device:
 * val session = AndroPilot.session()
 * session.observe()
 * session.click(Selector.text("Sign in"))
 * ```
 *
 * A single process-wide session is intentional. The screen is one shared resource; handing
 * out independent sessions would let two callers interleave gestures with no way to reason
 * about the result. Callers that want isolation should serialize at their own layer.
 */
public object AndroPilot {

    private const val TAG = ActionTrace.TAG

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var config: SessionConfig = SessionConfig.DEFAULT

    @Volatile
    private var sessionInstance: AndroPilotSession? = null

    private val _serviceConnected = MutableStateFlow(false)

    /** Emits true while the accessibility service is enabled and bound. */
    public val serviceConnected: StateFlow<Boolean> get() = _serviceConnected.asStateFlow()

    /**
     * Prepares the SDK. Safe to call more than once; a later call replaces the
     * configuration and discards the existing session.
     *
     * @param context any context; the application context is retained.
     * @param config tuning and policy. The default is safe for production: sensitive
     *   actions require confirmation and no screen text is logged.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, config: SessionConfig = SessionConfig.DEFAULT) {
        this.appContext = context.applicationContext
        this.config = config.withAndroidLoggerIfUnset()
        this.sessionInstance = null
        _serviceConnected.value = AndroPilotAccessibilityService.current() != null
    }

    /**
     * The process-wide session.
     *
     * Usable before the accessibility service is enabled: actions then fail with
     * `PERMISSION_REQUIRED` instead of throwing, so a host can build its UI against the
     * session and let the user grant access whenever they get round to it.
     *
     * @throws IllegalStateException if [initialize] has not been called.
     */
    @JvmStatic
    public fun session(): AndroPilotSession {
        sessionInstance?.let { return it }
        return synchronized(this) {
            sessionInstance ?: createSession().also { sessionInstance = it }
        }
    }

    /** True when the user has enabled the AndroPilot service in system settings. */
    @JvmStatic
    public fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context.packageName,
            AndroPilotAccessibilityService::class.java.name,
        ).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // The setting is a ':'-separated list of flattened component names.
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Sends the user to the accessibility settings screen.
     *
     * The SDK cannot and must not grant itself this permission -- that is the whole point of
     * Android's model here. Explain to the user, in your own UI, what the agent will be able
     * to do before sending them.
     */
    @JvmStatic
    public fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The active configuration, for inspection by host UI. */
    @JvmStatic
    public fun currentConfig(): SessionConfig = config

    private fun createSession(): AndroPilotSession {
        val context = appContext ?: error(
            "AndroPilot.initialize(context) must be called before session(). " +
                "Application.onCreate is the natural place.",
        )
        return DefaultAndroPilotSession(AccessibilityUiDriver(context), config)
    }

    internal fun onServiceConnected(service: AndroPilotAccessibilityService) {
        _serviceConnected.value = true
        log(LogLevel.INFO, "The accessibility service connected.")
    }

    internal fun onServiceDisconnected() {
        _serviceConnected.value = false
        log(LogLevel.WARN, "The accessibility service disconnected; actions will now fail.")
    }

    internal fun log(level: LogLevel, message: String) {
        config.logger.log(level, TAG, message, null)
    }

    /**
     * Wires the default logger to Logcat unless the host supplied one.
     *
     * Redaction still applies, so this is safe to leave on in release builds: it logs what
     * was attempted and why it failed, never what was on screen.
     */
    private fun SessionConfig.withAndroidLoggerIfUnset(): SessionConfig =
        if (logger === AgentLogger.NONE) copy(logger = LOGCAT) else this

    private val LOGCAT = AgentLogger { level, tag, message, error ->
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, error)
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
            LogLevel.NONE -> Unit
        }
    }
}
