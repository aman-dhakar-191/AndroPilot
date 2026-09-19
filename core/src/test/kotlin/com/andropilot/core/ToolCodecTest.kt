package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.SystemKey
import com.andropilot.core.action.UiCondition
import com.andropilot.core.model.Point
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.selector.ScreenRegion
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.session.ToolCodec
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire contract. These tests exist because the JSON shape is what an external agent
 * actually programs against, so a silent change to it is a breaking API change.
 */
class ToolCodecTest {

    @Test
    fun `every action round-trips through JSON unchanged`() {
        val actions = listOf<AgentAction>(
            AgentAction.Observe(includeVisual = true),
            AgentAction.Screenshot(maxDimension = 800),
            AgentAction.FindElement(Selector(text = "Sign in", region = ScreenRegion.BOTTOM)),
            AgentAction.ElementExists(Selector.id("submit")),
            AgentAction.Click(Selector.text("OK")),
            AgentAction.ClickPoint(Point(10, 20)),
            AgentAction.LongPress(Selector.text("Item"), durationMs = 900),
            AgentAction.TypeText(Selector.id("email"), "a@b.c", sensitive = true),
            AgentAction.ClearText(Selector.id("email")),
            AgentAction.Scroll(Direction.UP, steps = 3),
            AgentAction.ScrollUntil(UiCondition.TextVisible("Total"), Direction.DOWN),
            AgentAction.Swipe(Point(1, 2), Point(3, 4)),
            AgentAction.PressKey(SystemKey.BACK),
            AgentAction.LaunchApp("com.example.shop"),
            AgentAction.OpenIntent("android.intent.action.VIEW", "https://example.com"),
            AgentAction.WaitFor(UiCondition.PackageInForeground("com.example.shop")),
            AgentAction.Sleep(100),
            AgentAction.Verify(
                UiCondition.AllOf(
                    listOf(
                        UiCondition.ElementPresent(Selector.text("Done")),
                        UiCondition.ElementAbsent(Selector.text("Error")),
                    ),
                ),
            ),
        )
        actions.forEach { action ->
            val decoded = ToolCodec.decodeAction(ToolCodec.encodeAction(action))
            assertEquals(action, decoded, "round trip failed for ${action.name}")
        }
    }

    @Test
    fun `the type discriminator uses the documented action names`() {
        val json = ToolCodec.encodeAction(AgentAction.Click(Selector.text("OK")))
        assertTrue(json.contains("\"type\":\"click\""), json)
    }

    @Test
    fun `an agent can drive the SDK entirely through JSON`() = runTest {
        val session = DefaultAndroPilotSession(
            FakeUiDriver(FakeScreens.login()),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                postActionDelayMs = 0,
                policy = DefaultSafetyPolicy.permissive(),
            ),
        )
        val response = ToolCodec.executeJson(
            session,
            """{"type":"find_element","selector":{"text":"Sign in"}}""",
        )
        val decoded = ToolCodec.decodeResult(response)
        assertTrue(decoded.isSuccess)
        assertEquals("submit", (decoded as ActionResult.Success).target?.id)
    }

    @Test
    fun `a malformed payload comes back as a structured failure, not an exception`() = runTest {
        val session = DefaultAndroPilotSession(FakeUiDriver(FakeScreens.login()))
        val response = ToolCodec.executeJson(session, """{"type":"teleport"}""")
        val decoded = ToolCodec.decodeResult(response)
        val failure = assertTrue(decoded is ActionResult.Failure).let { decoded as ActionResult.Failure }
        assertEquals(
            com.andropilot.core.action.FailureReason.INVALID_REQUEST,
            failure.reason,
        )
        assertTrue(failure.recommendation!!.contains("click"))
    }

    @Test
    fun `unknown selector fields are ignored rather than rejected`() {
        val action = ToolCodec.decodeAction(
            """{"type":"click","selector":{"text":"OK","invented_field":42}}""",
        )
        assertEquals(Selector.text("OK"), (action as AgentAction.Click).selector)
    }

    @Test
    fun `tool descriptors cover every action name and carry descriptions`() {
        val descriptors = ToolCodec.toolDescriptors()
        val described = descriptors.map { it.name }.toSet()
        val missing = ToolCodec.ACTION_NAMES.toSet() - described
        assertTrue(missing.isEmpty()) { "actions with no tool descriptor: $missing" }
        descriptors.forEach {
            assertTrue(it.description.length > 30) { "${it.name} has a thin description" }
            assertEquals("object", it.parameterSchema["type"]?.jsonPrimitive?.content)
            assertNotNull(it.parameterSchema["properties"]?.jsonObject)
        }
    }

    @Test
    fun `the model summary omits screenshot bytes but keeps the dimensions`() = runTest {
        val session = DefaultAndroPilotSession(
            FakeUiDriver(FakeScreens.login()),
            SessionConfig(policy = DefaultSafetyPolicy.permissive()),
        )
        val result = session.execute(AgentAction.Screenshot())
        val summary = ToolCodec.summarizeForModel(result)
        assertTrue(summary.contains("1080x1920"))
        assertFalse(summary.contains("iVBOR")) // the base64 payload must not be inlined
    }

    @Test
    fun `a failure summary gives the model the reason, the hint and the candidates`() = runTest {
        val session = DefaultAndroPilotSession(
            FakeUiDriver(FakeScreens.duplicateButtons()),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                postActionDelayMs = 0, policy = DefaultSafetyPolicy.permissive(),
            ),
        )
        val summary = ToolCodec.summarizeForModel(session.click("Choose"))
        assertTrue(summary.startsWith("FAILED click"))
        assertTrue(summary.contains("ambiguous_target"))
        assertTrue(summary.contains("hint:"))
        assertTrue(summary.contains("candidates:"))
    }

    @Test
    fun `a success summary states whether the UI changed`() = runTest {
        val session = DefaultAndroPilotSession(
            FakeUiDriver(FakeScreens.login()),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                postActionDelayMs = 0, policy = DefaultSafetyPolicy.permissive(),
            ),
        )
        val summary = ToolCodec.summarizeForModel(
            session.typeText("sam@example.com", Selector.id("email")),
        )
        assertTrue(summary.startsWith("OK type_text"))
        assertTrue(summary.contains("changed"))
    }

    @Test
    fun `a full result round-trips including the snapshot`() = runTest {
        val session = DefaultAndroPilotSession(
            FakeUiDriver(FakeScreens.login()),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                policy = DefaultSafetyPolicy.permissive(),
            ),
        )
        val original = session.observe()
        val decoded = ToolCodec.decodeResult(ToolCodec.encodeResult(original))
        assertEquals(
            (original as ActionResult.Success).snapshot?.elements?.size,
            (decoded as ActionResult.Success).snapshot?.elements?.size,
        )
    }
}
