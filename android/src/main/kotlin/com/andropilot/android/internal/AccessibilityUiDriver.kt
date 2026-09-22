package com.andropilot.android.internal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.andropilot.android.AndroPilotAccessibilityService
import com.andropilot.core.action.AppMetadata
import com.andropilot.core.action.Direction
import com.andropilot.core.action.SystemKey
import com.andropilot.core.driver.DriverErrorKind
import com.andropilot.core.driver.DriverOutcome
import com.andropilot.core.driver.Screenshot
import com.andropilot.core.driver.UiDriver
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.Point
import com.andropilot.core.model.UiSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The Android implementation of [UiDriver].
 *
 * Everything platform-specific lives here: node traversal, gesture dispatch, screenshots,
 * app launching. It holds no policy and no retry logic -- those belong to the session, which
 * is testable without a device.
 *
 * Elements carry short ids; the tree path each one resolves to lives in
 * [UiSnapshot.nodeHandles], which this driver writes and reads and nothing else interprets.
 */
internal class AccessibilityUiDriver(
    private val appContext: Context,
    private val serviceProvider: () -> AndroPilotAccessibilityService? = {
        AndroPilotAccessibilityService.current()
    },
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : UiDriver {

    private val builder = SnapshotBuilder()
    private val snapshotCounter = AtomicLong(0)

    override val isConnected: Boolean get() = serviceProvider() != null

    /**
     * Why the driver cannot act.
     *
     * "Enabled in system settings" and "bound and running" are different facts, and
     * conflating them produces the worst possible diagnostic: the one case where they
     * disagree is a service the user has switched on that is not actually running, which is
     * exactly when an accurate message matters. The two states have different remedies, so
     * they get different messages.
     */
    override fun connectionProblem(): String? {
        if (isConnected) return null
        val enabledInSettings = runCatching {
            com.andropilot.android.AndroPilot.isServiceEnabled(appContext)
        }.getOrDefault(false)
        return if (enabledInSettings) {
            "The AndroPilot accessibility service is switched on in system settings but is " +
                "not running, so it has stopped or failed to start -- Android describes a " +
                "service in this state as malfunctioning. Toggle it off and on under " +
                "Settings > Accessibility > AndroPilot, and check Logcat for the cause."
        } else {
            "The AndroPilot accessibility service is not enabled. Send the user to " +
                "Settings > Accessibility > AndroPilot, or call " +
                "AndroPilot.openAccessibilitySettings()."
        }
    }

    private fun service(): AndroPilotAccessibilityService =
        serviceProvider() ?: throw com.andropilot.core.driver.DriverException(
            DriverErrorKind.NOT_CONNECTED,
            connectionProblem()!!,
        )

    // ---- Perception -------------------------------------------------------------------

    override suspend fun captureSnapshot(): UiSnapshot = withContext(io) {
        val svc = service()
        val metrics = DisplayMetricsReader.read(appContext)
        val root = svc.rootInActiveWindow
        try {
            val windows = runCatching { svc.windows.orEmpty() }.getOrDefault(emptyList())
            builder.build(
                rootNode = root,
                metrics = metrics,
                packageName = root?.packageName?.toString() ?: svc.lastForegroundPackage,
                windowTitle = root?.let { findWindowTitle(windows, it) },
                keyboardVisible = windows.any {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                },
                hasDialog = SnapshotBuilder.windowsIndicateDialog(windows),
                snapshotId = "a${snapshotCounter.incrementAndGet()}",
                capturedAt = System.currentTimeMillis(),
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root?.recycle() }
        }
    }

    override suspend fun foregroundPackage(): String? = withContext(io) {
        val svc = serviceProvider() ?: return@withContext null
        val root = svc.rootInActiveWindow
        try {
            root?.packageName?.toString() ?: svc.lastForegroundPackage
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root?.recycle() }
        }
    }

    /**
     * Screenshots require API 30. On older releases the SDK reports [DriverErrorKind.UNSUPPORTED]
     * rather than falling back to `MediaProjection`, which would demand a second, far more
     * intrusive user consent for a capability the SDK treats as optional.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotR(svc: AndroPilotAccessibilityService): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (continuation.isActive) continuation.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    override suspend fun screenshot(): Screenshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw com.andropilot.core.driver.DriverException(
                DriverErrorKind.UNSUPPORTED,
                "Screenshots require Android 11 (API 30) or newer.",
            )
        }
        val bitmap = try {
            takeScreenshotR(service())
        } catch (e: SecurityException) {
            // The platform throws this when the service's XML omits canTakeScreenshot. It
            // used to surface as a bare INTERNAL_ERROR carrying the framework's own wording
            // ("Services don't have the capability of taking the screenshot"), which tells
            // an integrator nothing about where to look.
            throw com.andropilot.core.driver.DriverException(
                DriverErrorKind.PERMISSION_REQUIRED,
                "The accessibility service is not allowed to take screenshots. Its " +
                    "configuration must declare android:canTakeScreenshot=\"true\"; if you " +
                    "override andropilot_accessibility_service.xml, add it there.",
                e,
            )
        } ?: throw com.andropilot.core.driver.DriverException(
            DriverErrorKind.ACTION_REJECTED,
            "The screenshot was refused. The screen may be marked FLAG_SECURE, or the " +
                "system may be rate-limiting capture.",
        )

        return withContext(io) {
            val stream = ByteArrayOutputStream(bitmap.byteCount / 4)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val shot = Screenshot(stream.toByteArray(), bitmap.width, bitmap.height, System.currentTimeMillis())
            bitmap.recycle()
            shot
        }
    }

    // ---- Semantic interaction ---------------------------------------------------------

    override suspend fun performClick(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        onNode(snapshot, elementId) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }

    override suspend fun performLongClick(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        onNode(snapshot, elementId) { it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }

    override suspend fun focus(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        onNode(snapshot, elementId) {
            it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                it.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }

    override suspend fun setText(snapshot: UiSnapshot, elementId: String, text: String): DriverOutcome =
        onNode(snapshot, elementId) { node ->
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

    override suspend fun performScroll(
        snapshot: UiSnapshot,
        elementId: String,
        direction: Direction,
    ): DriverOutcome = onNode(snapshot, elementId) { node ->
        // ACTION_SCROLL_FORWARD means "towards the end of the content", which is down for a
        // vertical list and right for a horizontal one. The direction-to-action mapping is
        // therefore the same for DOWN/RIGHT and for UP/LEFT.
        val action = when (direction) {
            Direction.DOWN, Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Direction.UP, Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        node.performAction(action)
    }

    override suspend fun performImeAction(): DriverOutcome = withContext(io) {
        val svc = service()
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return@withContext DriverOutcome.Rejected(
                DriverErrorKind.ACTION_REJECTED,
                "No field currently has input focus.",
            )
        try {
            if (focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                DriverOutcome.Ok
            } else {
                DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    "The focused field refused the IME action.",
                )
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { focused.recycle() }
        }
    }

    // ---- Gestures ---------------------------------------------------------------------

    override suspend fun tap(point: Point): DriverOutcome =
        dispatch(strokeOf(listOf(point), 1L, 50L), "tap")

    override suspend fun longPress(point: Point, durationMs: Long): DriverOutcome =
        dispatch(strokeOf(listOf(point), 1L, durationMs), "long press")

    override suspend fun swipe(start: Point, end: Point, durationMs: Long): DriverOutcome =
        dispatch(strokeOf(listOf(start, end), 1L, durationMs), "swipe")

    private fun strokeOf(points: List<Point>, startTime: Long, duration: Long): GestureDescription {
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startTime, duration.coerceIn(1, 60_000)))
            .build()
    }

    private suspend fun dispatch(gesture: GestureDescription, label: String): DriverOutcome {
        val svc = service()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(DriverOutcome.Ok)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(
                            DriverOutcome.Rejected(
                                DriverErrorKind.ACTION_REJECTED,
                                "The $label gesture was cancelled by the system. Another " +
                                    "gesture may have been in flight, or a system window " +
                                    "may have intercepted it.",
                            ),
                        )
                    }
                }
            }
            val accepted = svc.dispatchGesture(gesture, callback, null)
            if (!accepted && continuation.isActive) {
                continuation.resume(
                    DriverOutcome.Rejected(
                        DriverErrorKind.ACTION_REJECTED,
                        "The system refused to dispatch the $label gesture.",
                    ),
                )
            }
        }
    }

    // ---- System -----------------------------------------------------------------------

    override suspend fun pressKey(key: SystemKey): DriverOutcome = withContext(io) {
        val svc = service()
        val action = when (key) {
            SystemKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            SystemKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            SystemKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            SystemKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            SystemKey.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        if (svc.performGlobalAction(action)) {
            DriverOutcome.Ok
        } else {
            DriverOutcome.Rejected(
                DriverErrorKind.ACTION_REJECTED,
                "The system refused the global action ${key.name.lowercase()}.",
            )
        }
    }

    override suspend fun launchApp(packageName: String): DriverOutcome = withContext(io) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext DriverOutcome.Rejected(
                DriverErrorKind.APP_UNAVAILABLE,
                "'$packageName' is not installed, has no launcher activity, or is not " +
                    "visible to this app under Android 11+ package visibility rules.",
            )
        startExternal(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun listApps(): List<AppMetadata> = withContext(io) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        appContext.packageManager.queryIntentActivities(launcher, 0)
            .map { info ->
                AppMetadata(
                    label = info.loadLabel(appContext.packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun openIntent(
        action: String,
        uri: String?,
        packageName: String?,
        extras: Map<String, String>,
    ): DriverOutcome = withContext(io) {
        val intent = Intent(action).apply {
            uri?.let { data = Uri.parse(it) }
            packageName?.let { setPackage(it) }
            extras.forEach { (key, value) -> putExtra(key, value) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return@withContext DriverOutcome.Rejected(
                DriverErrorKind.APP_UNAVAILABLE,
                "No installed activity can handle the intent '$action'.",
            )
        }
        startExternal(intent)
    }

    private fun startExternal(intent: Intent): DriverOutcome = try {
        appContext.startActivity(intent)
        DriverOutcome.Ok
    } catch (e: SecurityException) {
        DriverOutcome.Rejected(
            DriverErrorKind.PERMISSION_REQUIRED,
            "The target activity refused to start: ${e.message}",
        )
    } catch (e: Exception) {
        DriverOutcome.Rejected(DriverErrorKind.INTERNAL, e.message ?: "The activity could not be started.")
    }

    override suspend fun gestureSafeArea(): Bounds =
        withContext(io) { DisplayMetricsReader.read(appContext).safeFrame }

    // ---- Node resolution ----------------------------------------------------------------

    /**
     * Re-resolves an element id against the *live* tree and runs [body] on it.
     *
     * Ids are tree paths, so resolution is a walk down the same child indices. If the
     * structure has changed the walk fails and the caller gets [DriverErrorKind.STALE_NODE]
     * rather than an action landing on whatever now occupies that slot -- the single most
     * important correctness property of this class.
     */
    private suspend fun onNode(
        snapshot: UiSnapshot,
        elementId: String,
        body: (AccessibilityNodeInfo) -> Boolean,
    ): DriverOutcome = withContext(io) {
        val svc = service()

        // Only ids this driver issued can be acted on. A caller-supplied string now resolves
        // to nothing instead of being walked as a path, so an agent cannot name a node the
        // SDK never reported.
        val path = snapshot.nodeHandles[elementId]
            ?: return@withContext DriverOutcome.Rejected(
                DriverErrorKind.STALE_NODE,
                "Element '$elementId' is not part of snapshot '${snapshot.snapshotId}'. " +
                    "Observe the screen again and use an id from the new snapshot.",
            )

        val root = svc.rootInActiveWindow
            ?: return@withContext DriverOutcome.Rejected(
                DriverErrorKind.STALE_NODE,
                "The active window no longer exposes a root node.",
            )
        var node: AccessibilityNodeInfo? = root
        val owned = ArrayList<AccessibilityNodeInfo>(8)
        try {
            val indices = path.split('.').drop(1).mapNotNull(String::toIntOrNull)
            for (index in indices) {
                val current = node ?: break
                if (index >= current.childCount) {
                    node = null
                    break
                }
                val child = runCatching { current.getChild(index) }.getOrNull()
                if (child == null) {
                    node = null
                    break
                }
                owned += child
                node = child
            }
            val target = node ?: return@withContext DriverOutcome.Rejected(
                DriverErrorKind.STALE_NODE,
                "Element '$elementId' no longer exists; the screen changed since it was observed.",
            )
            if (!target.isEnabled) {
                return@withContext DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    "Element '$elementId' is disabled.",
                )
            }
            if (body(target)) {
                DriverOutcome.Ok
            } else {
                DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    "The app refused the action on element '$elementId'.",
                )
            }
        } catch (e: Exception) {
            DriverOutcome.Rejected(DriverErrorKind.INTERNAL, e.message ?: "Node resolution failed.")
        } finally {
            @Suppress("DEPRECATION")
            owned.forEach { runCatching { it.recycle() } }
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    private fun findWindowTitle(
        windows: List<AccessibilityWindowInfo>,
        root: AccessibilityNodeInfo,
    ): String? = windows
        .firstOrNull { it.id == root.windowId }
        ?.title
        ?.toString()
        ?.takeIf { it.isNotBlank() }
}
