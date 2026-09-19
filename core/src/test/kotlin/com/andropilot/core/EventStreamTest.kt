package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** Captures everything, synchronously, the way a recorder would. */
private class Recording : AgentEventListener {
    val events = mutableListOf<AgentEvent>()
    override fun onEvent(event: AgentEvent) { events += event }
    inline fun <reified T : AgentEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

class EventStreamTest {

    private fun session(
        vararg listeners: AgentEventListener,
        screen: com.andropilot.core.testing.FakeScreen = FakeScreens.login(),
        policy: com.andropilot.core.safety.SafetyPolicy = DefaultSafetyPolicy.permissive(),
    ) = DefaultAndroPilotSession(
        FakeUiDriver(screen),
        SessionConfig(
            settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
            postActionDelayMs = 0, retryBackoffMs = 1,
            policy = policy,
            listeners = listeners.toList(),
        ),
    )

    @Test
    fun `a listener registered up front sees the whole session, starting with the first event`() = runTest {
        val log = Recording()
        session(log).observe()
        // A flow subscriber attaching after construction would have missed this.
        assertInstanceOf<AgentEvent.ActionStarted>(log.events.first())
    }

    @Test
    fun `an action brackets its work with started and finished`() = runTest {
        val log = Recording()
        session(log).click("Sign in")
        val started = log.ofType<AgentEvent.ActionStarted>().single()
        val finished = log.ofType<AgentEvent.ActionFinished>().single()
        assertEquals("click", started.action.name)
        assertEquals(started.sequence, finished.sequence)
        assertTrue(log.events.indexOf(started) < log.events.indexOf(finished))
    }

    @Test
    fun `sequence numbers increase across actions`() = runTest {
        val log = Recording()
        val s = session(log)
        s.observe()
        s.observe()
        assertEquals(listOf(1L, 2L), log.ofType<AgentEvent.ActionStarted>().map { it.sequence })
    }

    @Test
    fun `observing the screen emits a snapshot event`() = runTest {
        val log = Recording()
        session(log).observe()
        val snapshots = log.ofType<AgentEvent.SnapshotCaptured>()
        assertTrue(snapshots.isNotEmpty())
        assertEquals("com.example.shop", snapshots.first().snapshot.packageName)
    }

    @Test
    fun `a paused action and its answer both reach the stream`() = runTest {
        val log = Recording()
        val s = session(log, screen = FakeScreens.confirmDialog(), policy = DefaultSafetyPolicy())
        val blocked = assertInstanceOf<ActionResult.Failure>(s.click("Delete"))
        val required = log.ofType<AgentEvent.ConfirmationRequired>().single()
        assertEquals(blocked.pendingConfirmation!!.id, required.confirmation.id)

        s.resolveConfirmation(required.confirmation.id, ConfirmationOutcome.APPROVED)
        val resolved = log.ofType<AgentEvent.ConfirmationResolved>().single()
        assertEquals(ConfirmationOutcome.APPROVED, resolved.outcome)
    }

    @Test
    fun `a note can be dropped into the stream to label a run`() = runTest {
        val log = Recording()
        val s = session(log)
        s.note("starting checkout", mapOf("case" to "42"))
        val note = log.ofType<AgentEvent.Note>().single()
        assertEquals("starting checkout", note.message)
        assertEquals("42", note.data["case"])
    }

    @Test
    fun `a listener that throws never breaks the action`() = runTest {
        val exploding = AgentEventListener { error("listener is broken") }
        val result = session(exploding).observe()
        assertTrue(result.isSuccess) { "a broken listener took the action down with it" }
    }

    @Test
    fun `a listener added later stops receiving once closed`() = runTest {
        val log = Recording()
        val s = session()
        val handle = s.addEventListener(log)
        s.observe()
        val seen = log.events.size
        assertTrue(seen > 0)
        handle.close()
        s.observe()
        assertEquals(seen, log.events.size)
    }

    @Test
    fun `every event is summarizable for a live log`() = runTest {
        val log = Recording()
        val s = session(log)
        s.note("hello")
        s.click("Nonexistent")
        assertTrue(log.events.isNotEmpty())
        log.events.forEach { assertTrue(it.summarize().isNotBlank()) }
        assertTrue(log.events.any { it.summarize().contains("FAIL") })
    }

    @Test
    fun `results and snapshots stay available as filtered views of the one stream`() = runTest {
        val s = session()
        val results = mutableListOf<ActionResult>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            .launch { s.results.collect { results += it } }
        s.execute(AgentAction.FindElement(Selector.text("Sign in")))
        job.cancel()
        assertEquals(1, results.size)
        assertTrue(results.single().isSuccess)
    }
}
