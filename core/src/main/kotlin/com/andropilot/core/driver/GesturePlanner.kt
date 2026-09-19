package com.andropilot.core.driver

import com.andropilot.core.action.Direction
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.Point
import kotlin.math.roundToInt

/** A planned swipe in absolute device pixels. */
public data class GesturePath(
    val start: Point,
    val end: Point,
    val durationMs: Long,
)

/**
 * Turns "scroll this container down a bit" into coordinates that actually work.
 *
 * The rules here exist because naive gestures fail in specific, reproducible ways:
 *  - starting or ending inside the navigation bar or status bar hands the gesture to the
 *    system instead of the app;
 *  - starting at the very edge of the screen triggers back-gesture navigation;
 *  - a swipe that spans the full container often overshoots or is interpreted as a fling;
 *  - a swipe shorter than touch slop is dropped entirely.
 *
 * All of it is pure arithmetic, so every rule is unit-tested against synthetic geometry.
 */
public object GesturePlanner {

    /** Keep this far from the left/right edges to avoid the system back gesture. */
    public const val EDGE_GUARD_FRACTION: Double = 0.06

    /** A gesture shorter than this many pixels will not be recognised as a drag. */
    public const val MIN_TRAVEL_PX: Int = 24

    /**
     * Plans a scroll inside [container], clipped to [safeArea].
     *
     * @param amount fraction of the container's extent to travel, clamped to `0.1..0.9`.
     *   Staying under 1.0 leaves visual overlap between frames, which is what makes
     *   "scroll until I see X" reliable rather than skipping content.
     */
    public fun planScroll(
        container: Bounds,
        safeArea: Bounds,
        direction: Direction,
        amount: Double = 0.6,
        durationMs: Long = 300,
    ): GesturePath? {
        val area = container.intersection(safeArea) ?: return null
        if (area.isEmpty) return null

        val fraction = amount.coerceIn(0.1, 0.9)
        val horizontalGuard = (safeArea.width * EDGE_GUARD_FRACTION).roundToInt()
        val usable = Bounds(
            left = maxOf(area.left, safeArea.left + horizontalGuard),
            top = area.top,
            right = minOf(area.right, safeArea.right - horizontalGuard),
            bottom = area.bottom,
        )
        if (usable.isEmpty) return null

        // Inset so the gesture never begins on the container's own boundary, where a
        // parent container frequently intercepts the touch.
        val padded = usable.inset(minOf(usable.width, usable.height) / 10)
        val cx = padded.centerX
        val cy = padded.centerY

        // To reveal content *below*, the finger moves *up*. Hence the inversion.
        val path = when (direction) {
            Direction.DOWN -> {
                val travel = (padded.height * fraction / 2).roundToInt()
                GesturePath(Point(cx, cy + travel), Point(cx, cy - travel), durationMs)
            }
            Direction.UP -> {
                val travel = (padded.height * fraction / 2).roundToInt()
                GesturePath(Point(cx, cy - travel), Point(cx, cy + travel), durationMs)
            }
            Direction.RIGHT -> {
                val travel = (padded.width * fraction / 2).roundToInt()
                GesturePath(Point(cx + travel, cy), Point(cx - travel, cy), durationMs)
            }
            Direction.LEFT -> {
                val travel = (padded.width * fraction / 2).roundToInt()
                GesturePath(Point(cx - travel, cy), Point(cx + travel, cy), durationMs)
            }
        }
        return path.takeIf { travelOf(it) >= MIN_TRAVEL_PX }?.let { clamp(it, safeArea) }
    }

    /** Clamps a caller-supplied swipe into the safe area without changing its character. */
    public fun sanitizeSwipe(path: GesturePath, safeArea: Bounds): GesturePath? {
        val clamped = clamp(path, safeArea)
        return clamped.takeIf { travelOf(it) >= MIN_TRAVEL_PX }
    }

    /** The point a tap should use: the element centre, pulled inside the safe area. */
    public fun tapPointFor(bounds: Bounds, safeArea: Bounds): Point? {
        if (bounds.isEmpty) return null
        val visible = bounds.intersection(safeArea) ?: return null
        if (visible.isEmpty) return null
        return visible.center
    }

    private fun travelOf(path: GesturePath): Int =
        maxOf(
            kotlin.math.abs(path.end.x - path.start.x),
            kotlin.math.abs(path.end.y - path.start.y),
        )

    private fun clamp(path: GesturePath, safeArea: Bounds): GesturePath = GesturePath(
        start = clampPoint(path.start, safeArea),
        end = clampPoint(path.end, safeArea),
        durationMs = path.durationMs.coerceIn(50, 10_000),
    )

    private fun clampPoint(point: Point, area: Bounds): Point = Point(
        point.x.coerceIn(area.left, maxOf(area.left, area.right - 1)),
        point.y.coerceIn(area.top, maxOf(area.top, area.bottom - 1)),
    )
}
