package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.InteractionMode
import com.andropilot.core.action.SystemKey
import com.andropilot.core.model.Bounds
import com.andropilot.core.observe.ActionTrace
import com.andropilot.core.observe.AgentLogger
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.Redactor
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.PolicyDecision
import com.andropilot.core.safety.RiskLevel
import com.andropilot.core.selector.Selector
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.Ui
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class SafetyTest {

    private val policy = DefaultSafetyPolicy()
    private val screen = FakeScreens.login().toSnapshot()

    private fun button(label: String) =
        Ui.button("b", label, Bounds(0, 0, 100, 100))

    @Nested
    inner class Classification {

        @Test
        fun `observation is read-only`() {
            assertEquals(RiskLevel.READ_ONLY, policy.classify(AgentAction.Observe(), null))
            assertEquals(RiskLevel.READ_ONLY, policy.classify(AgentAction.Screenshot(), null))
        }

        @Test
        fun `scrolling and back are navigation`() {
            assertEquals(RiskLevel.NAVIGATION, policy.classify(AgentAction.Scroll(Direction.DOWN), null))
            assertEquals(
                RiskLevel.NAVIGATION,
                policy.classify(AgentAction.PressKey(SystemKey.BACK), null),
            )
        }

        @Test
        fun `an ordinary button tap is mutating, not sensitive`() {
            val action = AgentAction.Click(Selector.text("Continue"))
            assertEquals(RiskLevel.MUTATING, policy.classify(action, button("Continue")))
        }

        @Test
        fun `destructive and financial labels are sensitive`() {
            val action = AgentAction.Click(Selector.text("x"))
            listOf("Delete", "Send", "Pay now", "Confirm order", "Transfer", "Buy").forEach { label ->
                assertEquals(
                    RiskLevel.SENSITIVE,
                    policy.classify(action, button(label)),
                    "\"$label\" should be sensitive",
                )
            }
        }

        @Test
        fun `keyword matching is word-bounded, so Resend is not Send`() {
            val action = AgentAction.Click(Selector.text("x"))
            assertEquals(RiskLevel.MUTATING, policy.classify(action, button("Addendum")))
            assertEquals(RiskLevel.MUTATING, policy.classify(action, button("Deleted items")))
            // A genuine standalone keyword still matches, punctuation and all.
            assertEquals(RiskLevel.SENSITIVE, policy.classify(action, button("Send.")))
        }

        @Test
        fun `a password field is sensitive whatever it is labelled`() {
            val field = Ui.field("p", "Anything", Bounds(0, 0, 10, 10), password = true)
            val action = AgentAction.TypeText(null, "value")
            assertEquals(RiskLevel.SENSITIVE, policy.classify(action, field))
        }

        @Test
        fun `text explicitly marked sensitive is sensitive regardless of the target`() {
            val action = AgentAction.TypeText(null, "123456", sensitive = true)
            assertEquals(RiskLevel.SENSITIVE, policy.classify(action, button("Code")))
        }

        @Test
        fun `a custom keyword can be added by the host`() {
            val custom = DefaultSafetyPolicy(extraSensitiveKeywords = setOf("yeet"))
            assertEquals(
                RiskLevel.SENSITIVE,
                custom.classify(AgentAction.Click(Selector.text("x")), button("Yeet it")),
            )
        }
    }

    @Nested
    inner class Gating {

        @Test
        fun `the default policy allows navigation and confirms sensitive actions`() {
            assertInstanceOf<PolicyDecision.Allow>(
                policy.evaluate(AgentAction.Observe(), screen, null),
            )
            val decision = policy.evaluate(
                AgentAction.Click(Selector.text("Delete")), screen, button("Delete"),
            )
            val confirm = assertInstanceOf<PolicyDecision.RequireConfirmation>(decision)
            assertTrue(confirm.reasons.any { it.contains("delete", ignoreCase = true) })
        }

        @Test
        fun `a strict policy confirms anything with a side effect`() {
            val decision = DefaultSafetyPolicy.strict()
                .evaluate(AgentAction.Click(Selector.text("Continue")), screen, button("Continue"))
            assertInstanceOf<PolicyDecision.RequireConfirmation>(decision)
        }

        @Test
        fun `an app outside the allow-list cannot be driven or launched`() {
            val restricted = DefaultSafetyPolicy(allowedPackages = setOf("com.example.other"))
            assertInstanceOf<PolicyDecision.Deny>(
                restricted.evaluate(AgentAction.LaunchApp("com.example.shop"), screen, null),
            )
            assertInstanceOf<PolicyDecision.Deny>(
                restricted.evaluate(
                    AgentAction.Click(Selector.text("Sign in")), screen, button("Sign in"),
                ),
            )
        }

        @Test
        fun `an allow-listed app is still only restricted for mutating actions`() {
            val restricted = DefaultSafetyPolicy(allowedPackages = setOf("com.example.other"))
            // Reading a non-allow-listed screen is not a side effect, so it is not denied.
            assertFalse(
                restricted.evaluate(AgentAction.Observe(), screen, null) is PolicyDecision.Deny,
            )
        }

        @Test
        fun `only allow-listed intent actions are permitted`() {
            assertInstanceOf<PolicyDecision.Allow>(
                policy.evaluate(
                    AgentAction.OpenIntent("android.intent.action.VIEW", "https://example.com"),
                    screen, null,
                ),
            )
            assertInstanceOf<PolicyDecision.Deny>(
                policy.evaluate(AgentAction.OpenIntent("android.intent.action.SENDTO"), screen, null),
            )
        }
    }

    @Nested
    inner class Observability {

        @Test
        fun `redaction is on by default and reports only the length`() {
            val redactor = Redactor()
            val rendered = redactor.describeAction(AgentAction.TypeText(null, "hunter2"))
            assertFalse(rendered.contains("hunter2"))
            assertTrue(rendered.contains("7 chars"))
        }

        @Test
        fun `text is shown only when a host explicitly opts in`() {
            val redactor = Redactor(allowTextContent = true)
            assertTrue(redactor.describeAction(AgentAction.TypeText(null, "hello")).contains("hello"))
        }

        @Test
        fun `values marked sensitive stay redacted even when text logging is enabled`() {
            val redactor = Redactor(allowTextContent = true)
            val rendered = redactor.describeAction(
                AgentAction.TypeText(null, "123456", sensitive = true),
            )
            assertFalse(rendered.contains("123456"))
        }

        @Test
        fun `the trace is bounded and keeps the most recent entries`() {
            val trace = ActionTrace(capacity = 3)
            repeat(10) { i ->
                trace.record(
                    ActionResult.Success(
                        action = AgentAction.Sleep(i.toLong()),
                        durationMs = 1,
                        interactionMode = InteractionMode.NONE,
                    ),
                    startedAt = 0,
                )
            }
            val entries = trace.snapshot()
            assertEquals(3, entries.size)
            assertEquals(listOf(8L, 9L, 10L), entries.map { it.sequence })
        }

        @Test
        fun `failures are logged at WARN and successes at INFO`() {
            val seen = mutableListOf<LogLevel>()
            val trace = ActionTrace(
                logger = AgentLogger { level, _, _, _ -> seen += level },
            )
            trace.record(
                ActionResult.Success(AgentAction.Observe(), 1, InteractionMode.NONE),
                startedAt = 0,
            )
            trace.record(
                ActionResult.Failure(
                    AgentAction.Observe(), 1,
                    com.andropilot.core.action.FailureReason.TIMEOUT, "nope",
                ),
                startedAt = 0,
            )
            assertEquals(listOf(LogLevel.INFO, LogLevel.WARN), seen)
        }

        @Test
        fun `a trace entry states the interaction mode and whether the UI changed`() {
            val trace = ActionTrace()
            val entry = trace.record(
                ActionResult.Success(
                    action = AgentAction.Click(Selector.text("OK")),
                    durationMs = 42,
                    interactionMode = InteractionMode.GESTURE,
                    diff = com.andropilot.core.model.UiDiff.NONE,
                ),
                startedAt = 0,
            )
            val text = entry.format()
            assertTrue(text.contains("gesture"))
            assertTrue(text.contains("42ms"))
            assertTrue(text.contains("no ui change"))
        }
    }
}
