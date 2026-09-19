package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.model.Bounds
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.RiskCategory
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreen
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import com.andropilot.core.testing.Ui
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * Severity and kind of harm are separate axes. "Ask me about money, but posting and deleting
 * can proceed" is an ordinary position that a single scale cannot express, because paying
 * and deleting sit at the same point on it.
 */
class RiskCategoryTest {

    private val policy = DefaultSafetyPolicy()

    private fun button(label: String) = Ui.button("b", label, Bounds(0, 0, 400, 120))
    private fun click() = AgentAction.Click(Selector.text("x"))

    @Test
    fun `labels are sorted by the kind of harm they do`() {
        mapOf(
            "Pay now" to RiskCategory.FINANCIAL,
            "Buy" to RiskCategory.FINANCIAL,
            "Transfer" to RiskCategory.FINANCIAL,
            "Delete" to RiskCategory.DESTRUCTIVE,
            "Uninstall" to RiskCategory.DESTRUCTIVE,
            "Send" to RiskCategory.COMMUNICATION,
            "Post" to RiskCategory.COMMUNICATION,
            "Sign out" to RiskCategory.CREDENTIAL,
            "Continue" to RiskCategory.OTHER,
        ).forEach { (label, expected) ->
            assertEquals(expected, policy.categorize(click(), button(label)), label)
        }
    }

    @Test
    fun `a structural credential marker beats whatever the label says`() {
        val field = Ui.field("p", "Pay", Bounds(0, 0, 10, 10), password = true)
        assertEquals(RiskCategory.CREDENTIAL, policy.categorize(click(), field))
        assertEquals(
            RiskCategory.CREDENTIAL,
            policy.categorize(AgentAction.TypeText(null, "123456", sensitive = true), null),
        )
    }

    // ---- the configuration this exists for ----------------------------------------------

    private fun shop(label: String): FakeScreen {
        val screen = FakeScreens.login()
        screen.add(Ui.button("target", label, Bounds(60, 1400, 1020, 1520)))
        screen.update("root") { it.copy(childIds = it.childIds + "target") }
        screen.clickHandler = { s, e -> if (e.id == "target") s.remove("title") }
        return screen
    }

    private fun session(screen: FakeScreen) = DefaultAndroPilotSession(
        FakeUiDriver(screen),
        SessionConfig(
            settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
            postActionDelayMs = 0, retryBackoffMs = 1,
            policy = DefaultSafetyPolicy.financialOnly(),
        ),
    )

    @Test
    fun `money asks`() = runTest {
        val result = assertInstanceOf<ActionResult.Failure>(session(shop("Pay now")).click("Pay now"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, result.reason)
    }

    @Test
    fun `destroying does not, because this host said it cares about money`() = runTest {
        assertTrue(session(shop("Delete account")).click("Delete account").isSuccess)
    }

    @Test
    fun `sending does not either`() = runTest {
        assertTrue(session(shop("Send")).click("Send").isSuccess)
    }

    @Test
    fun `ordinary work is untouched`() = runTest {
        assertTrue(session(shop("Continue")).click("Continue").isSuccess)
    }

    @Test
    fun `a financial prompt waits rather than expiring, so it can be answered later`() = runTest {
        val s = session(shop("Pay now"))
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Pay now"))
        assertEquals(1, s.pendingConfirmations().size)

        // Approving whenever someone reaches the phone runs it.
        val ran = s.resolveConfirmation(
            blocked.pendingConfirmation!!.id,
            com.andropilot.core.safety.ConfirmationOutcome.APPROVED,
        )
        assertTrue(ran!!.isSuccess)
    }

    @Test
    fun `the default still gates every category`() = runTest {
        val s = DefaultAndroPilotSession(
            FakeUiDriver(shop("Delete account")),
            SessionConfig(
                settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                postActionDelayMs = 0, policy = DefaultSafetyPolicy(),
            ),
        )
        val result = assertInstanceOf<ActionResult.Failure>(s.click("Delete account"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, result.reason)
    }
}
