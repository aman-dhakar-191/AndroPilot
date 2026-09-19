package com.andropilot.core.testing

import com.andropilot.core.action.Direction
import com.andropilot.core.action.SystemKey
import com.andropilot.core.driver.DriverErrorKind
import com.andropilot.core.driver.DriverOutcome
import com.andropilot.core.driver.Screenshot
import com.andropilot.core.driver.UiDriver
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.Point
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot

/**
 * An in-memory [UiDriver] backed by a scripted sequence of screens.
 *
 * This is shipped in the main source set on purpose: an app integrating the SDK should be
 * able to unit-test its own agent logic -- "when the login screen appears, my planner types
 * the username" -- without an emulator. It is also what the SDK's own test suite runs
 * against.
 *
 * Screens are supplied as [FakeScreen]s; interactions mutate the current screen through
 * declarative [ScreenTransition]s.
 */
public open class FakeUiDriver(
    initial: FakeScreen,
    public var connected: Boolean = true,
) : UiDriver {

    /** Every low-level call made, in order. Assert against this in tests. */
    public val calls: MutableList<String> = mutableListOf()

    /** Number of snapshots captured, to assert that the session is not over-polling. */
    public var captureCount: Int = 0
        private set

    /** Force the next N node operations to report a stale node. */
    public var staleNodeCountdown: Int = 0

    /** Force every gesture to be rejected, to exercise fallback paths. */
    public var rejectGestures: Boolean = false

    /** Force every semantic action to be rejected, to exercise gesture fallback. */
    public var rejectSemanticActions: Boolean = false

    public var screen: FakeScreen = initial
        private set

    private var screenshotBytes: ByteArray = PNG_STUB

    public fun setScreen(next: FakeScreen) {
        screen = next
    }

    public fun setScreenshot(bytes: ByteArray) {
        screenshotBytes = bytes
    }

    override val isConnected: Boolean get() = connected

    override fun connectionProblem(): String? =
        if (connected) null else "The fake driver is disconnected."

    override suspend fun captureSnapshot(): UiSnapshot {
        captureCount++
        return screen.toSnapshot()
    }

    override suspend fun foregroundPackage(): String? = screen.packageName

    override suspend fun screenshot(): Screenshot = Screenshot(
        pngBytes = screenshotBytes,
        width = screen.metrics.widthPx,
        height = screen.metrics.heightPx,
        capturedAt = 0L,
    )

    override suspend fun performClick(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        semantic("click", snapshot, elementId) { screen.simulateClick(it) }

    override suspend fun performLongClick(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        semantic("longClick", snapshot, elementId) { screen.simulateLongClick(it) }

    override suspend fun setText(snapshot: UiSnapshot, elementId: String, text: String): DriverOutcome =
        semantic("setText", snapshot, elementId) { screen.simulateSetText(it, text) }

    override suspend fun focus(snapshot: UiSnapshot, elementId: String): DriverOutcome =
        semantic("focus", snapshot, elementId) { screen.simulateFocus(it) }

    override suspend fun performScroll(
        snapshot: UiSnapshot,
        elementId: String,
        direction: Direction,
    ): DriverOutcome = semantic("scroll:$direction", snapshot, elementId) {
        screen.simulateScroll(it, direction)
    }

    override suspend fun performImeAction(): DriverOutcome {
        calls += "imeAction"
        return DriverOutcome.Ok
    }

    override suspend fun tap(point: Point): DriverOutcome = gesture("tap(${point.x},${point.y})") {
        val hit = screen.elements.lastOrNull { point in it.bounds && it.clickable }
        if (hit != null) screen.simulateClick(hit)
    }

    override suspend fun longPress(point: Point, durationMs: Long): DriverOutcome =
        gesture("longPress(${point.x},${point.y})") {
            screen.elements.lastOrNull { point in it.bounds }?.let(screen::simulateLongClick)
        }

    override suspend fun swipe(start: Point, end: Point, durationMs: Long): DriverOutcome =
        gesture("swipe(${start.x},${start.y}->${end.x},${end.y})") {
            val direction = when {
                kotlin.math.abs(end.y - start.y) >= kotlin.math.abs(end.x - start.x) ->
                    if (end.y < start.y) Direction.DOWN else Direction.UP
                end.x < start.x -> Direction.RIGHT
                else -> Direction.LEFT
            }
            screen.elements.firstOrNull { it.scrollable }?.let { screen.simulateScroll(it, direction) }
        }

    override suspend fun pressKey(key: SystemKey): DriverOutcome {
        calls += "key:$key"
        screen.simulateKey(key)
        return DriverOutcome.Ok
    }

    override suspend fun launchApp(packageName: String): DriverOutcome {
        calls += "launch:$packageName"
        return screen.simulateLaunch(packageName)
    }

    override suspend fun openIntent(
        action: String,
        uri: String?,
        packageName: String?,
        extras: Map<String, String>,
    ): DriverOutcome {
        calls += "intent:$action"
        return DriverOutcome.Ok
    }

    override suspend fun gestureSafeArea(): Bounds = screen.metrics.safeFrame

    private inline fun semantic(
        label: String,
        snapshot: UiSnapshot,
        elementId: String,
        body: (UiElement) -> Unit,
    ): DriverOutcome {
        calls += "$label:$elementId"
        if (staleNodeCountdown > 0) {
            staleNodeCountdown--
            return DriverOutcome.Rejected(DriverErrorKind.STALE_NODE, "The node no longer exists.")
        }
        if (rejectSemanticActions) {
            return DriverOutcome.Rejected(DriverErrorKind.ACTION_REJECTED, "Semantic actions are disabled.")
        }
        val element = screen.elements.firstOrNull { it.id == elementId }
            ?: return DriverOutcome.Rejected(DriverErrorKind.STALE_NODE, "No node '$elementId'.")
        body(element)
        return DriverOutcome.Ok
    }

    private inline fun gesture(label: String, body: () -> Unit): DriverOutcome {
        calls += label
        if (rejectGestures) {
            return DriverOutcome.Rejected(DriverErrorKind.ACTION_REJECTED, "Gestures are disabled.")
        }
        body()
        return DriverOutcome.Ok
    }

    private companion object {
        /** A 1x1 transparent PNG, enough for tests that only check plumbing. */
        val PNG_STUB: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }
}
