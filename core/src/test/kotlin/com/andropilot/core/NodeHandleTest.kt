package com.andropilot.core

import com.andropilot.core.action.ActionData
import com.andropilot.core.action.ActionResult
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.session.ToolCodec
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import com.andropilot.core.testing.Ui
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * Element ids are short and meaningless; how a driver finds the element again rides in
 * [UiSnapshot.nodeHandles]. These pin the properties that separation depends on.
 */
class NodeHandleTest {

    private val handles = mapOf("e0" to "0", "e1" to "0.3", "e2" to "0.3.1")

    private fun snapshotWith(vararg ids: String) = UiSnapshot(
        snapshotId = "s1",
        capturedAt = 0,
        packageName = "com.example",
        elements = ids.mapIndexed { i, id ->
            Ui.button(id, "Button $i", Bounds(0, i * 100, 100, i * 100 + 90))
        },
        nodeHandles = handles.filterKeys { it in ids },
    )

    @Test
    fun `handles survive the copies the pipeline makes`() {
        // Vision fusion, element capping and id stamping all go through copy(); a handle
        // table lost at any of those points would make every action fail as stale.
        val original = snapshotWith("e0", "e1")
        val restamped = original.copy(snapshotId = "s9")
        assertEquals(handles.filterKeys { it != "e2" }, restamped.nodeHandles)
    }

    @Test
    fun `handles never cross the wire`() {
        val json = ToolCodec.json.encodeToString(UiSnapshot.serializer(), snapshotWith("e0", "e1"))
        assertFalse(json.contains("nodeHandles")) { json }
        assertFalse(json.contains("0.3")) { json }
        // The ids themselves must still travel, since that is what an agent names.
        assertTrue(json.contains("\"e1\""))
    }

    @Test
    fun `a snapshot that came back from an agent carries no handles`() {
        val sent = ToolCodec.json.encodeToString(UiSnapshot.serializer(), snapshotWith("e0", "e1"))
        val received = ToolCodec.json.decodeFromString(UiSnapshot.serializer(), sent)
        // The device resolves against its own copy, so this is correct rather than lossy --
        // and it means a driver cannot be handed a path by a remote caller.
        assertTrue(received.nodeHandles.isEmpty())
        assertEquals(2, received.elements.size)
    }

    @Test
    fun `truncating a snapshot drops the handles of the elements it removed`() = runTest {
        val screen = FakeScreens.login()
        repeat(200) { i -> screen.add(Ui.label("pad$i", "padding $i", Bounds(0, i, 10, i + 5))) }
        val session = DefaultAndroPilotSession(
            FakeUiDriver(screen),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                postActionDelayMs = 0, maxElements = 20,
                policy = DefaultSafetyPolicy.permissive(),
            ),
        )
        val result = assertInstanceOf<ActionResult.Success>(session.observe())
        val snapshot = (result.data as ActionData.SnapshotData).snapshot
        val ids = snapshot.elements.map { it.id }.toSet()
        // An id the agent can no longer see must not remain actionable.
        assertTrue(snapshot.nodeHandles.keys.all { it in ids }) {
            "orphaned handles: ${snapshot.nodeHandles.keys - ids}"
        }
    }

    @Test
    fun `ids stay short enough not to dominate the rendering`() {
        val text = FakeScreens.deeplyWrappedSettings().toSnapshot().toCompactText()
        val longest = Regex("\\[([^\\]]+)\\] ").findAll(text)
            .map { it.groupValues[1].length }
            .maxOrNull() ?: 0
        // A real capture rendered ids of 30 characters when they encoded the tree path.
        assertTrue(longest <= 16) { "an id of $longest characters appeared:\n$text" }
    }
}
