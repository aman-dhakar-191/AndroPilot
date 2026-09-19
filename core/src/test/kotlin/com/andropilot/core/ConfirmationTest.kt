package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.FailureReason
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * Approving a sensitive action should run it. The alternative -- telling the host to reissue
 * the action itself -- was the shape of the API before, and a real device trace showed the
 * cost: three round trips where one would do, with nothing in the failure saying to retry.
 */
class ConfirmationTest {

    private var now = 1_000L

    private fun session(validityMs: Long = 120_000) = DefaultAndroPilotSession(
        FakeUiDriver(
            FakeScreens.confirmDialog().apply {
                clickHandler = { s, e -> if (e.id == "confirm") s.remove("dialog_title") }
            },
        ),
        SessionConfig(
            settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
            postActionDelayMs = 0, retryBackoffMs = 1,
            policy = DefaultSafetyPolicy(),
            confirmationValidityMs = validityMs,
        ),
        clock = { now },
    )

    @Test
    fun `approving runs the action it was holding`() = runTest {
        val s = session()
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, blocked.reason)

        val result = s.resolveConfirmation(
            blocked.pendingConfirmation!!.id,
            ConfirmationOutcome.APPROVED,
        )
        // The host does not have to know to reissue it.
        assertTrue(assertInstanceOf<ActionResult>(result).isSuccess)
    }

    @Test
    fun `rejecting runs nothing and leaves the action blocked`() = runTest {
        val s = session()
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertNull(s.resolveConfirmation(blocked.pendingConfirmation!!.id, ConfirmationOutcome.REJECTED))

        val again = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, again.reason)
    }

    @Test
    fun `an unknown confirmation id resolves to nothing`() = runTest {
        assertNull(session().resolveConfirmation("nope", ConfirmationOutcome.APPROVED))
    }

    @Test
    fun `an approval covers one action, not a standing permission`() = runTest {
        val s = session()
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        s.resolveConfirmation(blocked.pendingConfirmation!!.id, ConfirmationOutcome.APPROVED)

        val third = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, third.reason)
    }

    @Test
    fun `an approval nobody redeemed expires instead of waiting forever`() = runTest {
        val s = session(validityMs = 60_000)
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        val id = blocked.pendingConfirmation!!.id

        // Granted, but the run it triggers is discarded -- as if the screen had moved on.
        s.resolveConfirmation(id, ConfirmationOutcome.APPROVED)

        // A human approved what they were shown then, not whatever matches an hour later.
        now += 60_001
        val later = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(FailureReason.CONFIRMATION_REQUIRED, later.reason)
    }

    @Test
    fun `a pending confirmation is no longer pending once answered`() = runTest {
        val s = session()
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        assertEquals(1, s.pendingConfirmations().size)
        s.resolveConfirmation(blocked.pendingConfirmation!!.id, ConfirmationOutcome.REJECTED)
        assertTrue(s.pendingConfirmations().isEmpty())
    }
}
