package com.andropilot.core

import com.andropilot.core.action.ActionData
import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.action.InteractionMode
import com.andropilot.core.action.SystemKey
import com.andropilot.core.action.UiCondition
import com.andropilot.core.model.Bounds
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import com.andropilot.core.testing.Ui
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * End-to-end behaviour of the session against scripted screens.
 *
 * These cover the SDK's actual value proposition -- verification, retries, fallbacks,
 * safety gating -- without touching Android.
 */
class SessionTest {

    /**
     * Timings are collapsed so the suite runs in milliseconds. The settle loop is still
     * exercised; it just converges immediately because the fake screen is stable.
     */
    private fun config(
        policy: com.andropilot.core.safety.SafetyPolicy = DefaultSafetyPolicy.permissive(),
        treatNoEffectAsFailure: Boolean = true,
        visionProvider: com.andropilot.core.vision.VisionProvider? = null,
    ) = SessionConfig(
        settleTimeoutMs = 50,
        settleQuietPeriodMs = 0,
        pollIntervalMs = 1,
        postActionDelayMs = 0,
        defaultWaitTimeoutMs = 100,
        retryBackoffMs = 1,
        policy = policy,
        treatNoEffectAsFailure = treatNoEffectAsFailure,
        visionProvider = visionProvider,
    )

    private fun session(
        driver: FakeUiDriver,
        config: SessionConfig = config(),
    ) = DefaultAndroPilotSession(driver, config)

    // ---- Observation -------------------------------------------------------------------

    @Test
    fun `observe returns a snapshot with the foreground package and elements`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).observe()
        val success = assertInstanceOf<ActionResult.Success>(result)
        val data = assertInstanceOf<ActionData.SnapshotData>(success.data)
        assertEquals("com.example.shop", data.snapshot.packageName)
        assertTrue(data.snapshot.elements.any { it.id == "submit" })
    }

    @Test
    fun `observe reports no_perception when the window exposes nothing`() = runTest {
        val empty = com.andropilot.core.testing.FakeScreen("com.example.blank")
        val result = session(FakeUiDriver(empty)).observe()
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.NO_PERCEPTION, failure.reason)
        assertNotNull(failure.recommendation)
    }

    @Test
    fun `the compact rendering stays small and mentions every interactive element`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).observe() as ActionResult.Success
        val text = (result.data as ActionData.SnapshotData).snapshot.toCompactText()
        assertTrue(text.contains("Sign in"))
        assertTrue(text.contains("editable"))
        assertTrue(text.lines().size < 20) { "compact text was unexpectedly long:\n$text" }
    }

    // ---- Clicking ----------------------------------------------------------------------

    @Test
    fun `a click uses the semantic action and reports the resulting change`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val s = session(driver)
        s.typeText("sam@example.com", Selector.id("email"))
        val result = s.click("Sign in")
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals(InteractionMode.SEMANTIC, success.interactionMode)
        assertTrue(success.uiChanged)
        assertTrue(driver.calls.any { it.startsWith("click:submit") })
    }

    @Test
    fun `a click falls back to a gesture when the semantic action is refused`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        driver.rejectSemanticActions = true
        val result = session(driver).click(Selector.text("Sign in"))
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals(InteractionMode.GESTURE, success.interactionMode)
        assertTrue(success.warnings.any { it.contains("fell back", ignoreCase = true) })
        assertTrue(driver.calls.any { it.startsWith("tap(") })
    }

    @Test
    fun `an action that changes nothing is reported as no_effect`() = runTest {
        val screen = FakeScreens.login()
        screen.clickHandler = { _, _ -> } // the app swallows the tap
        val result = session(FakeUiDriver(screen)).click("Forgot password?")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.NO_EFFECT, failure.reason)
    }

    @Test
    fun `no_effect can be disabled for apps with no visible feedback`() = runTest {
        val screen = FakeScreens.login()
        screen.clickHandler = { _, _ -> }
        val result = session(FakeUiDriver(screen), config(treatNoEffectAsFailure = false))
            .click("Forgot password?")
        assertInstanceOf<ActionResult.Success>(result)
    }

    @Test
    fun `a missing element fails with actionable diagnostics`() = runTest {
        val result = session(FakeUiDriver(FakeScreens.login())).click("Checkout")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.ELEMENT_NOT_FOUND, failure.reason)
        assertNotNull(failure.snapshot) // the agent can re-plan without another round trip
        assertNotNull(failure.recommendation)
    }

    @Test
    fun `an ambiguous target fails without acting`() = runTest {
        val driver = FakeUiDriver(FakeScreens.duplicateButtons())
        val result = session(driver).click("Choose")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.AMBIGUOUS_TARGET, failure.reason)
        assertEquals(2, failure.candidates.size)
        assertFalse(driver.calls.any { it.startsWith("click:") })
    }

    @Test
    fun `a disabled target fails immediately rather than retrying`() = runTest {
        val screen = FakeScreens.login()
        screen.update("submit") { it.copy(enabled = false) }
        val driver = FakeUiDriver(screen)
        val result = session(driver).click("Sign in")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.ELEMENT_NOT_ACTIONABLE, failure.reason)
        assertFalse(failure.isTransient)
    }

    @Test
    fun `a stale node is retried and succeeds on the next attempt`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        driver.staleNodeCountdown = 1
        val s = session(driver)
        s.typeText("sam@example.com", Selector.id("email"))
        val result = s.click("Sign in")
        assertInstanceOf<ActionResult.Success>(result)
    }

    @Test
    fun `retries are bounded and a stale node never becomes a blind coordinate tap`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        driver.staleNodeCountdown = 99
        val result = session(driver).click("Sign in")
        assertFalse(driver.calls.any { it.startsWith("tap(") })
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.STALE_ELEMENT, failure.reason)
    }

    // ---- Text entry ---------------------------------------------------------------------

    @Test
    fun `typing targets a field by selector and verifies the text landed`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).typeText("sam@example.com", Selector(text = "Email address"))
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals("sam@example.com", success.snapshot?.get("user")?.text)
    }

    @Test
    fun `typing without a selector uses the focused field`() = runTest {
        val screen = FakeScreens.login()
        screen.update("user") { it.copy(focused = true) }
        val driver = FakeUiDriver(screen)
        val result = session(driver).typeText("hello")
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals("hello", success.snapshot?.get("user")?.text)
    }

    @Test
    fun `typing with no selector and no focus fails with a clear explanation`() = runTest {
        val driver = FakeUiDriver(FakeScreens.duplicateButtons())
        val failure = assertInstanceOf<ActionResult.Failure>(session(driver).typeText("hello"))
        assertEquals(FailureReason.INVALID_REQUEST, failure.reason)
        assertTrue(failure.message.contains("selector"))
    }

    @Test
    fun `clear_text empties the field`() = runTest {
        val screen = FakeScreens.login()
        screen.update("user") { it.copy(text = "old@example.com") }
        val driver = FakeUiDriver(screen)
        val result = session(driver).execute(AgentAction.ClearText(Selector.id("email")))
        assertInstanceOf<ActionResult.Success>(result)
        assertEquals("", driver.screen.elements.first { it.id == "user" }.text)
    }

    @Test
    fun `sensitive text never reaches the trace`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val s = session(driver)
        s.typeText("hunter2-secret", Selector.id("password"), sensitive = true)
        val trace = s.trace().joinToString("\n") { it.actionDescription }
        assertFalse(trace.contains("hunter2"))
        assertTrue(trace.contains("redacted"))
    }

    // ---- Scrolling ----------------------------------------------------------------------

    @Test
    fun `scroll_until finds a row below the fold`() = runTest {
        val driver = FakeUiDriver(FakeScreens.settingsList())
        val result = session(driver).execute(
            AgentAction.ScrollUntil(
                until = UiCondition.ElementPresent(Selector.text("Delete account")),
                direction = Direction.DOWN,
            ),
        )
        assertInstanceOf<ActionResult.Success>(result)
    }

    @Test
    fun `scroll_until stops when the list reaches its end`() = runTest {
        val screen = FakeScreens.settingsList()
        screen.scrollStepPx = 0 // already at the bottom
        val result = session(FakeUiDriver(screen)).execute(
            AgentAction.ScrollUntil(
                until = UiCondition.ElementPresent(Selector.text("Nonexistent row")),
            ),
        )
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.TIMEOUT, failure.reason)
        assertTrue(failure.message.contains("stopped moving"))
    }

    @Test
    fun `scroll_until returns immediately when the condition already holds`() = runTest {
        val driver = FakeUiDriver(FakeScreens.settingsList())
        val result = session(driver).execute(
            AgentAction.ScrollUntil(
                until = UiCondition.ElementPresent(Selector.text("Setting option 3")),
            ),
        )
        assertInstanceOf<ActionResult.Success>(result)
        assertFalse(driver.calls.any { it.startsWith("scroll:") || it.startsWith("swipe(") })
    }

    @Test
    fun `scrolling with no scrollable container fails clearly`() = runTest {
        val result = session(FakeUiDriver(FakeScreens.login()))
            .execute(AgentAction.Scroll(Direction.DOWN))
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.ELEMENT_NOT_FOUND, failure.reason)
    }

    @Test
    fun `a scroll selector pointing at a row scrolls its list ancestor`() = runTest {
        val driver = FakeUiDriver(FakeScreens.settingsList())
        val result = session(driver).execute(
            AgentAction.Scroll(Direction.DOWN, selector = Selector.text("Setting option 1")),
        )
        assertInstanceOf<ActionResult.Success>(result)
        assertTrue(driver.calls.any { it.contains("settings_list") })
    }

    // ---- App control --------------------------------------------------------------------

    @Test
    fun `launching an app waits for it to reach the foreground`() = runTest {
        val driver = FakeUiDriver(FakeScreens.launcher())
        val result = session(driver).launchApp("com.example.shop")
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals("com.example.shop", success.snapshot?.packageName)
    }

    @Test
    fun `launching a missing app reports app_unavailable`() = runTest {
        val driver = FakeUiDriver(FakeScreens.launcher())
        val result = session(driver).launchApp("com.example.missing")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.APP_UNAVAILABLE, failure.reason)
    }

    @Test
    fun `back is dispatched as a system action`() = runTest {
        val screen = FakeScreens.confirmDialog()
        screen.keyHandler = { s, key ->
            if (key == SystemKey.BACK) {
                s.hasDialog = false
                s.replaceAll(FakeScreens.home().elements)
            }
        }
        val driver = FakeUiDriver(screen)
        val result = session(driver).back()
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertEquals(InteractionMode.SYSTEM, success.interactionMode)
        assertTrue(success.diff?.dialogDismissed == true)
    }

    // ---- Waiting and verification -------------------------------------------------------

    @Test
    fun `wait_for times out with a diagnostic naming what is on screen`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).waitFor(
            UiCondition.ElementPresent(Selector.text("Order complete")),
            timeoutMs = 20,
        )
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.TIMEOUT, failure.reason)
        assertTrue(failure.recommendation!!.contains("Visible labels"))
    }

    @Test
    fun `verify succeeds when the expected state is already present`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).execute(
            AgentAction.Verify(UiCondition.TextVisible("Welcome back"), timeoutMs = 20),
        )
        assertInstanceOf<ActionResult.Success>(result)
    }

    @Test
    fun `element_exists answers without acting`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val s = session(driver)
        assertTrue(s.exists(Selector.text("Sign in")))
        assertFalse(s.exists(Selector.text("Checkout")))
    }

    // ---- Safety -------------------------------------------------------------------------

    @Test
    fun `a sensitive button requires confirmation before it is tapped`() = runTest {
        val driver = FakeUiDriver(FakeScreens.confirmDialog())
        val s = session(driver, config(policy = DefaultSafetyPolicy()))
        val result = s.click("Delete")
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, failure.reason)
        assertEquals(RiskLevel.SENSITIVE, failure.pendingConfirmation?.risk)
        assertFalse(driver.calls.any { it.startsWith("click:confirm") })
    }

    @Test
    fun `an approved confirmation runs the action, and covers only that one`() = runTest {
        val screen = FakeScreens.confirmDialog()
        screen.clickHandler = { s, e -> if (e.id == "confirm") s.remove("dialog_title") }
        val driver = FakeUiDriver(screen)
        val s = session(driver, config(policy = DefaultSafetyPolicy()))

        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        // Approving runs it; the caller does not reissue the action.
        val ran = s.resolveConfirmation(blocked.pendingConfirmation!!.id, ConfirmationOutcome.APPROVED)
        assertTrue(ran!!.isSuccess)

        // The approval covered that one action, not a standing permission.
        val third = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, third.reason)
    }

    @Test
    fun `a rejected confirmation keeps blocking the action`() = runTest {
        val driver = FakeUiDriver(FakeScreens.confirmDialog())
        val s = session(driver, config(policy = DefaultSafetyPolicy()))
        val first = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        s.resolveConfirmation(first.pendingConfirmation!!.id, ConfirmationOutcome.REJECTED)
        val second = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, second.reason)
    }

    @Test
    fun `a password field is classified sensitive regardless of its label`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val s = session(driver, config(policy = DefaultSafetyPolicy()))
        val result = s.typeText("anything", Selector.id("password"))
        assertEquals(
            FailureReason.CONFIRMATION_REQUIRED,
            assertInstanceOf<ActionResult.Failure>(result).reason,
        )
    }

    @Test
    fun `navigation is never gated by the default policy`() = runTest {
        val driver = FakeUiDriver(FakeScreens.settingsList())
        val s = session(driver, config(policy = DefaultSafetyPolicy()))
        assertInstanceOf<ActionResult.Success>(s.execute(AgentAction.Scroll(Direction.DOWN)))
        assertInstanceOf<ActionResult.Success>(s.observe())
    }

    @Test
    fun `an intent outside the allow-list is denied`() = runTest {
        val driver = FakeUiDriver(FakeScreens.home())
        val s = session(driver, config(policy = DefaultSafetyPolicy()))
        val result = s.execute(AgentAction.OpenIntent("android.intent.action.CALL", "tel:911"))
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.BLOCKED_BY_POLICY, failure.reason)
    }

    // ---- Coordinate fallback --------------------------------------------------------------

    @Test
    fun `a coordinate tap warns when semantic alternatives existed`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).execute(
            AgentAction.ClickPoint(com.andropilot.core.model.Point(540, 990)),
        )
        val success = assertInstanceOf<ActionResult.Success>(result)
        assertTrue(success.warnings.any { it.contains("coordinate tap") })
    }

    @Test
    fun `a coordinate tap outside the safe area is rejected`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val result = session(driver).execute(
            AgentAction.ClickPoint(com.andropilot.core.model.Point(540, 1900)),
        )
        val failure = assertInstanceOf<ActionResult.Failure>(result)
        assertEquals(FailureReason.INVALID_REQUEST, failure.reason)
    }

    // ---- Session mechanics ----------------------------------------------------------------

    @Test
    fun `a disconnected driver reports permission_required, not a crash`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login(), connected = false)
        val s = session(driver)
        assertFalse(s.isReady)
        val failure = assertInstanceOf<ActionResult.Failure>(s.click("Sign in"))
        assertEquals(FailureReason.PERMISSION_REQUIRED, failure.reason)
        assertNotNull(failure.recommendation)
    }

    @Test
    fun `executeAll stops at the first failure by default`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val results = session(driver).executeAll(
            listOf(
                AgentAction.Observe(),
                AgentAction.Click(Selector.text("Nonexistent")),
                AgentAction.Observe(),
            ),
        )
        assertEquals(2, results.size)
    }

    @Test
    fun `executeAll can be told to continue past failures`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val results = session(driver).executeAll(
            listOf(
                AgentAction.Observe(),
                AgentAction.Click(Selector.text("Nonexistent")),
                AgentAction.Observe(),
            ),
            continueOnFailure = true,
        )
        assertEquals(3, results.size)
        assertTrue(results.last().isSuccess)
    }

    @Test
    fun `the trace records what was attempted and how`() = runTest {
        val driver = FakeUiDriver(FakeScreens.login())
        val s = session(driver)
        s.observe()
        s.click("Nonexistent")
        val trace = s.trace()
        assertEquals(2, trace.size)
        assertTrue(trace[1].format().contains("element_not_found"))
        assertTrue(trace[0].sequence < trace[1].sequence)
    }

    @Test
    fun `a huge hierarchy is truncated with a warning rather than being sent whole`() = runTest {
        val screen = FakeScreens.login()
        repeat(500) { i ->
            screen.add(Ui.label("pad$i", "padding $i", Bounds(0, i, 10, i + 5)))
        }
        val driver = FakeUiDriver(screen)
        val s = DefaultAndroPilotSession(driver, config().copy(maxElements = 50))
        val result = assertInstanceOf<ActionResult.Success>(s.observe())
        val snapshot = (result.data as ActionData.SnapshotData).snapshot
        assertEquals(50, snapshot.elements.size)
        assertTrue(snapshot.warnings.any { it.contains("truncated") })
        // The interactive elements survive truncation; the padding is what gets dropped.
        assertTrue(snapshot.elements.any { it.id == "submit" })
    }
}
