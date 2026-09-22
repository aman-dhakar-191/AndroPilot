package com.andropilot.core.driver

import com.andropilot.core.action.Direction
import com.andropilot.core.action.AppMetadata
import com.andropilot.core.action.SystemKey
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.Point
import com.andropilot.core.model.UiSnapshot

/**
 * A raw screenshot, kept as encoded PNG bytes.
 *
 * The core module never decodes images -- that is platform work and a dependency the core
 * does not want. Vision providers and hosts decode as needed.
 */
public class Screenshot(
    public val pngBytes: ByteArray,
    public val width: Int,
    public val height: Int,
    public val capturedAt: Long,
) {
    public val sizeBytes: Int get() = pngBytes.size
    override fun toString(): String = "Screenshot(${width}x$height, ${pngBytes.size} bytes)"
}

/** Why a low-level device operation did not happen. */
public enum class DriverErrorKind {
    NOT_CONNECTED,
    PERMISSION_REQUIRED,
    STALE_NODE,
    ACTION_REJECTED,
    UNSUPPORTED,
    APP_UNAVAILABLE,
    TIMEOUT,
    INTERNAL,
}

public class DriverException(
    public val kind: DriverErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Outcome of a single low-level operation. */
public sealed interface DriverOutcome {
    public data object Ok : DriverOutcome
    public data class Rejected(val kind: DriverErrorKind, val message: String) : DriverOutcome
}

/**
 * The platform boundary of the SDK.
 *
 * Everything above this interface -- matching, verification, retries, safety, tracing -- is
 * pure Kotlin and runs on any JVM. Everything below it is Android. That split is what makes
 * the interesting logic testable without a device, and what would let a future
 * remote-control transport slot in by implementing this one interface over a wire protocol.
 *
 * Implementations must be safe to call from any coroutine dispatcher; they are expected to
 * hop to whatever thread the platform requires internally.
 *
 * Node handles: [performNodeAction] and friends take the [com.andropilot.core.model.UiElement.id]
 * from a snapshot the driver itself produced. Drivers are responsible for re-resolving that
 * id against the live tree and reporting [DriverErrorKind.STALE_NODE] when it no longer
 * exists, rather than silently acting on the wrong node.
 */
public interface UiDriver {

    /** True when the driver can currently observe and act. */
    public val isConnected: Boolean

    /** A short description of what is missing when [isConnected] is false. */
    public fun connectionProblem(): String?

    /** Captures the current screen as a normalized snapshot. */
    public suspend fun captureSnapshot(): UiSnapshot

    /** The foreground package, or null when it cannot be determined. */
    public suspend fun foregroundPackage(): String?

    public suspend fun screenshot(): Screenshot

    // ---- Semantic interaction ---------------------------------------------------------

    /** Performs `ACTION_CLICK` on the node backing [elementId]. */
    public suspend fun performClick(snapshot: UiSnapshot, elementId: String): DriverOutcome

    public suspend fun performLongClick(snapshot: UiSnapshot, elementId: String): DriverOutcome

    /** Performs `ACTION_SET_TEXT`, replacing the field contents. */
    public suspend fun setText(snapshot: UiSnapshot, elementId: String, text: String): DriverOutcome

    public suspend fun focus(snapshot: UiSnapshot, elementId: String): DriverOutcome

    /** Performs `ACTION_SCROLL_FORWARD` / `_BACKWARD` on a scrollable node. */
    public suspend fun performScroll(
        snapshot: UiSnapshot,
        elementId: String,
        direction: Direction,
    ): DriverOutcome

    /** Triggers the IME action (search / next / done) on the focused field. */
    public suspend fun performImeAction(): DriverOutcome

    // ---- Gesture interaction ----------------------------------------------------------

    public suspend fun tap(point: Point): DriverOutcome

    public suspend fun longPress(point: Point, durationMs: Long): DriverOutcome

    public suspend fun swipe(start: Point, end: Point, durationMs: Long): DriverOutcome

    // ---- System -----------------------------------------------------------------------

    public suspend fun pressKey(key: SystemKey): DriverOutcome

    public suspend fun launchApp(packageName: String): DriverOutcome

    /** Lists apps with launcher entries, for choosing a package before [launchApp]. */
    public suspend fun listApps(): List<AppMetadata> = emptyList()

    public suspend fun openIntent(
        action: String,
        uri: String?,
        packageName: String?,
        extras: Map<String, String>,
    ): DriverOutcome

    /** The region gestures may safely target, normally the display minus system insets. */
    public suspend fun gestureSafeArea(): Bounds
}
