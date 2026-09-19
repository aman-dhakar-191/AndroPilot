package com.andropilot.android.internal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.andropilot.core.model.Insets
import com.andropilot.core.model.Orientation
import com.andropilot.core.model.ScreenMetrics

/**
 * Reads the display geometry the gesture planner needs.
 *
 * Deliberately not cached: orientation, window size (on foldables and in multi-window) and
 * even insets change at runtime, and a stale value produces gestures that land in the wrong
 * place. The read is cheap relative to a tree traversal.
 */
internal object DisplayMetricsReader {

    fun read(context: Context): ScreenMetrics {
        val configuration = context.resources.configuration
        val density = context.resources.displayMetrics.density
        val orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Orientation.LANDSCAPE
        } else {
            Orientation.PORTRAIT
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val metrics = windowManager.maximumWindowMetrics
            val bounds = metrics.bounds
            val systemBars = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            ScreenMetrics(
                widthPx = bounds.width(),
                heightPx = bounds.height(),
                density = density,
                orientation = orientation,
                systemInsets = Insets(
                    left = systemBars.left,
                    top = systemBars.top,
                    right = systemBars.right,
                    bottom = systemBars.bottom,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { display.getRealSize(it) }
            // Pre-R has no reliable inset query from a non-UI context. A conservative
            // status/navigation bar estimate is used instead: overshooting the safe area
            // costs a few unusable pixels, undershooting costs swallowed gestures.
            val statusBar = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }
                ?.let { context.resources.getDimensionPixelSize(it) }
                ?: (24 * density).toInt()
            val navBar = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                .takeIf { it > 0 }
                ?.let { context.resources.getDimensionPixelSize(it) }
                ?: (48 * density).toInt()
            ScreenMetrics(
                widthPx = size.x,
                heightPx = size.y,
                density = density,
                orientation = orientation,
                systemInsets = Insets(top = statusBar, bottom = navBar),
            )
        }
    }
}
