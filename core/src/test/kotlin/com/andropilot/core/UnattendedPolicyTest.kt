package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * A confirmation and a denial differ only when someone is there to answer. With nobody at
 * the device a confirmation is a block that never clears, and the agent discovers that only
 * by timing out.
 */
class UnattendedPolicyTest {

    private fun session(
        policy: com.andropilot.core.safety.SafetyPolicy,
        screen: com.andropilot.core.testing.FakeScreen = FakeScreens.confirmDialog(),
    ) = DefaultAndroPilotSession(
        FakeUiDriver(screen),
        SessionConfig(
            settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
            postActionDelayMs = 0, retryBackoffMs = 1,
            policy = policy,
            // These screens do not react to a tap, and whether the UI changed is not what
            // is under test here -- only whether the policy let the action reach the driver.
            treatNoEffectAsFailure = false,
        ),
    )

    @Test
    fun `an unattended session is never left holding a prompt nobody can answer`() = runTest {
        val s = session(DefaultSafetyPolicy.unattended())
        val result = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.BLOCKED_BY_POLICY, result.reason)
        assertTrue(s.pendingConfirmations().isEmpty())
    }

    @Test
    fun `the denial names the risk, so an agent can re-plan rather than retry`() = runTest {
        val s = session(DefaultSafetyPolicy.unattended())
        val result = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertTrue(result.message.contains("sensitive")) { result.message }
        assertTrue(result.message.contains("delete", ignoreCase = true)) { result.message }
        // BLOCKED_BY_POLICY is not transient, so the retry loop does not grind on it.
        assertTrue(!result.reason.isTransient)
    }

    @Test
    fun `ordinary work still runs`() = runTest {
        val s = session(DefaultSafetyPolicy.unattended(), FakeScreens.settingsList())
        assertTrue(s.observe().isSuccess)
        assertTrue(s.execute(AgentAction.Scroll(Direction.DOWN)).isSuccess)
        assertTrue(s.click("Setting option 2").isSuccess)
    }

    @Test
    fun `the ceiling can be lowered when even ordinary changes are too much`() = runTest {
        val s = session(
            DefaultSafetyPolicy.unattended(denyAtOrAbove = RiskLevel.MUTATING),
            FakeScreens.settingsList(),
        )
        // Navigation is still fine; anything that changes state is not.
        assertTrue(s.execute(AgentAction.Scroll(Direction.DOWN)).isSuccess)
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Setting option 2"))
        assertEquals(FailureReason.BLOCKED_BY_POLICY, blocked.reason)
    }

    @Test
    fun `permissive would have let the same tap through, which is the difference`() = runTest {
        // Both never ask. Only one of them draws a line.
        val s = session(DefaultSafetyPolicy.permissive())
        assertTrue(s.click("Delete").isSuccess)
    }
}
