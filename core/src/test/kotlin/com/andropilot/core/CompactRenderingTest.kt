package com.andropilot.core

import com.andropilot.core.testing.FakeScreens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The compact rendering is what an agent actually reads, and a real Android hierarchy is
 * mostly noise. These tests pin the "compact" part against a capture-shaped screen.
 */
class CompactRenderingTest {

    private val settings = FakeScreens.deeplyWrappedSettings().toSnapshot()

    @Test
    fun `a ten-deep wrapper chain does not reach the agent`() {
        val text = settings.toCompactText()
        assertFalse(text.contains("wrapper_")) { "layout wrappers leaked:\n$text" }
    }

    @Test
    fun `zero-sized stubs do not reach the agent`() {
        assertFalse(settings.toCompactText().contains("search_mode_stub"))
    }

    @Test
    fun `every meaningful row survives`() {
        val text = settings.toCompactText()
        listOf("Network & internet", "Connected devices", "Apps", "Notifications")
            .forEach { assertTrue(text.contains(it)) { "\"$it\" was dropped:\n$text" } }
    }

    @Test
    fun `the scrollable container survives, because an agent needs it to scroll`() {
        assertTrue(settings.toCompactText().contains("scrollable"))
    }

    @Test
    fun `the rendering is a handful of lines, not one per node`() {
        val lines = settings.toCompactText().trim().lines()
        // 1 header + 1 list + 4 rows. The wrapper spine and the stub contribute nothing.
        assertEquals(6, lines.size) { "unexpected rendering:\n${lines.joinToString("\n")}" }
    }

    @Test
    fun `indentation reflects retained ancestors, not raw tree depth`() {
        val rowLine = settings.toCompactText().lines().first { it.contains("Network & internet") }
        val indent = rowLine.takeWhile { it == ' ' }.length
        // The row is 11 levels deep in the raw tree; only the list is retained above it.
        assertEquals(2, indent) { "row was indented $indent spaces: '$rowLine'" }
    }

    @Test
    fun `branch points are kept so siblings stay distinguishable`() {
        // The dialog screen branches: the dialog owns a title and two buttons.
        val text = FakeScreens.confirmDialog().toSnapshot().toCompactText()
        assertTrue(text.contains("dialog"))
        assertTrue(text.contains("Cancel"))
        assertTrue(text.contains("Delete"))
    }
}
