package com.andropilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned rectangle in **device pixels**, using Android's screen coordinate
 * convention: origin at the top-left of the display, x growing right, y growing down.
 *
 * Bounds are always reported in pixels because that is what gesture dispatch consumes.
 * Use [ScreenMetrics.toDp] if a density-independent value is needed.
 */
@Serializable
public data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    public val width: Int get() = right - left
    public val height: Int get() = bottom - top
    public val area: Long get() = width.toLong() * height.toLong()
    public val centerX: Int get() = left + width / 2
    public val centerY: Int get() = top + height / 2
    public val center: Point get() = Point(centerX, centerY)

    /** True when the rectangle has no positive area; such elements are not tappable. */
    public val isEmpty: Boolean get() = width <= 0 || height <= 0

    public operator fun contains(point: Point): Boolean =
        point.x in left until right && point.y in top until bottom

    public fun contains(other: Bounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    public fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    public fun intersection(other: Bounds): Bounds? {
        if (!intersects(other)) return null
        return Bounds(
            max(left, other.left),
            max(top, other.top),
            min(right, other.right),
            min(bottom, other.bottom),
        )
    }

    /**
     * Intersection-over-union with [other]. Used to correlate semantic elements with
     * visually detected ones, and to decide whether an element "moved" between snapshots.
     */
    public fun iou(other: Bounds): Double {
        val inter = intersection(other)?.area ?: return 0.0
        val union = area + other.area - inter
        return if (union <= 0L) 0.0 else inter.toDouble() / union.toDouble()
    }

    /** Clamps this rectangle so it stays inside [frame]; returns null if fully outside. */
    public fun clampedTo(frame: Bounds): Bounds? = intersection(frame)

    /** Shrinks the rectangle by [px] on each side, never below a 1px box around the centre. */
    public fun inset(px: Int): Bounds {
        val l = min(left + px, centerX)
        val t = min(top + px, centerY)
        val r = max(right - px, centerX + 1)
        val b = max(bottom - px, centerY + 1)
        return Bounds(l, t, r, b)
    }

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    public companion object {
        public val EMPTY: Bounds = Bounds(0, 0, 0, 0)

        public fun ofSize(width: Int, height: Int): Bounds = Bounds(0, 0, width, height)
    }
}

@Serializable
public data class Point(val x: Int, val y: Int) {
    public fun distanceTo(other: Point): Double {
        val dx = (x - other.x).toDouble()
        val dy = (y - other.y).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    public fun manhattanTo(other: Point): Int = abs(x - other.x) + abs(y - other.y)
}

@Serializable
public enum class Orientation {
    @SerialName("portrait") PORTRAIT,
    @SerialName("landscape") LANDSCAPE,
}

/**
 * Physical characteristics of the display the snapshot was taken from.
 *
 * An AI agent needs these to reason about *relative* positions ("the button near the
 * bottom of the screen") without the SDK having to bake in device-specific assumptions.
 */
@Serializable
public data class ScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    /** Logical density (px per dp). 1.0 == mdpi. */
    val density: Float,
    val orientation: Orientation,
    /**
     * Insets that are occupied by system UI (status bar, navigation bar, cutouts).
     * Gestures that start or end inside these regions are unreliable, so the gesture
     * planner keeps away from them.
     */
    val systemInsets: Insets = Insets.ZERO,
) {
    val frame: Bounds get() = Bounds(0, 0, widthPx, heightPx)

    /** The area safe for synthesized gestures. */
    val safeFrame: Bounds
        get() = Bounds(
            systemInsets.left,
            systemInsets.top,
            widthPx - systemInsets.right,
            heightPx - systemInsets.bottom,
        )

    public fun toDp(px: Int): Float = if (density <= 0f) px.toFloat() else px / density

    public fun toPx(dp: Float): Int = if (density <= 0f) dp.toInt() else (dp * density).toInt()

    public companion object {
        /** A neutral 1080x1920 xxhdpi phone, used as a default in tests and previews. */
        public val DEFAULT: ScreenMetrics =
            ScreenMetrics(1080, 1920, 3.0f, Orientation.PORTRAIT)
    }
}

@Serializable
public data class Insets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    public companion object {
        public val ZERO: Insets = Insets()
    }
}
