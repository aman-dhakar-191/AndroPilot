package com.andropilot.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.andropilot.core.observe.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The privileged half of the SDK.
 *
 * An `AccessibilityService` is the only sanctioned way on stock Android for one app to read
 * another app's UI and inject input, and it is the right choice here precisely *because* it
 * is user-granted, revocable, visible in system settings, and honours `FLAG_SECURE`. The
 * alternatives -- root, a shell-side `uiautomator` bridge, or instrumentation -- either
 * cannot ship to real users or require privileges the SDK has no business asking for.
 *
 * The service exposes itself through a process-wide holder rather than binding: an
 * accessibility service is created by the system, not by the host app, so the host's code
 * has to discover it rather than construct it. Access is a weak, null-checked handle so a
 * disabled service degrades to a clean `PERMISSION_REQUIRED` instead of a crash.
 *
 * ### Threading
 * Callbacks arrive on the main thread. Tree traversal is comparatively expensive, so the
 * driver hops to a background dispatcher and only touches the service object itself, whose
 * relevant methods are thread-safe.
 */
public class AndroPilotAccessibilityService : AccessibilityService() {

    private val eventCounter = AtomicLong(0)

    private val _lastEventAt = MutableStateFlow(0L)

    /** Timestamp of the most recent window/content event, used by settle detection. */
    public val lastEventAt: StateFlow<Long> get() = _lastEventAt.asStateFlow()

    /** Package of the window that most recently took focus. */
    @Volatile
    public var lastForegroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        instance = this
        AndroPilot.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        eventCounter.incrementAndGet()
        _lastEventAt.value = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastForegroundPackage = it }
        }
    }

    override fun onInterrupt() {
        // Required by the platform. Nothing to abandon: the driver never holds a long-lived
        // operation that an interrupt would need to cancel.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AndroPilot.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        AndroPilot.onServiceDisconnected()
        super.onDestroy()
    }

    internal fun log(level: LogLevel, message: String) {
        AndroPilot.log(level, message)
    }

    public companion object {
        @Volatile
        private var instance: AndroPilotAccessibilityService? = null

        /** The connected service, or null when the user has not enabled it. */
        public fun current(): AndroPilotAccessibilityService? = instance
    }
}
