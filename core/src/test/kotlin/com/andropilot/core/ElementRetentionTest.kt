package com.andropilot.core

import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRetention
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.UiElement
import com.andropilot.core.testing.Ui
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that stands between an agent and a screenful of layout scaffolding.
 *
 * The zero-area cases below are taken from a real `com.android.settings` capture, where a
 * scrolled-away list arrived as `[1220,284][1220,2397]` and an inverted row as
 * `[223,2820][1046,2712]`.
 */
class ElementRetentionTest {

    private fun container(bounds: Bounds, resourceId: String? = null, visible: Boolean = true) =
        UiElement(
            id = "x",
            role = ElementRole.CONTAINER,
            bounds = bounds,
            resourceId = resourceId,
            visible = visible,
        )

    @Test
    fun `anything with a surviving child is kept, however dull`() {
        val empty = container(Bounds(0, 2712, 0, 2712))
        assertTrue(ElementRetention.shouldKeep(empty, hasSurvivingChildren = true))
    }

    @Test
    fun `a container whose children were all dropped is dropped too`() {
        // The bug this test exists for: judging by the LIVE child count kept these, because
        // the node did have children -- they had simply all been filtered away.
        val scrolledAwayList = container(
            Bounds(1220, 284, 1220, 2397),
            resourceId = "com.mi.android.globallauncher:id/apps_list_view",
        )
        assertFalse(ElementRetention.shouldKeep(scrolledAwayList, hasSurvivingChildren = false))
    }

    @Test
    fun `a resource id does not rescue a node with no area`() {
        val collapsedBar = container(
            Bounds(0, 2712, 0, 2712),
            resourceId = "com.android.settings:id/split_action_bar",
        )
        assertFalse(ElementRetention.shouldKeep(collapsedBar, hasSurvivingChildren = false))
    }

    @Test
    fun `inverted bounds count as no area`() {
        // bottom above top: a row scrolled past the end of its list.
        val inverted = container(Bounds(223, 2820, 1046, 2712))
        assertFalse(ElementRetention.shouldKeep(inverted, hasSurvivingChildren = false))
    }

    @Test
    fun `an off-screen node is dropped even when it has size and a label`() {
        val hidden = Ui.button("b", "Send", Bounds(0, 0, 100, 100)).copy(visible = false)
        assertFalse(ElementRetention.shouldKeep(hidden, hasSurvivingChildren = false))
    }

    @Test
    fun `a labelled leaf is kept`() {
        val label = Ui.label("t", "About phone", Bounds(223, 699, 511, 764))
        assertTrue(ElementRetention.shouldKeep(label, hasSurvivingChildren = false))
    }

    @Test
    fun `an unlabelled but actionable leaf is kept, since an agent can still use it`() {
        val icon = Ui.unlabelledIcon("i", Bounds(60, 1700, 400, 1800))
        assertTrue(ElementRetention.shouldKeep(icon, hasSurvivingChildren = false))
    }

    @Test
    fun `an identified leaf is kept, because a selector can name it`() {
        val stub = container(Bounds(0, 296, 1080, 400), resourceId = "com.example:id/anchor")
        assertTrue(ElementRetention.shouldKeep(stub, hasSurvivingChildren = false))
    }

    @Test
    fun `a blank sized leaf with nothing to identify it is dropped`() {
        assertFalse(
            ElementRetention.shouldKeep(
                container(Bounds(0, 0, 100, 100)),
                hasSurvivingChildren = false,
            ),
        )
    }
}
