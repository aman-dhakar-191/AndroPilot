package com.andropilot.core.session

import com.andropilot.core.action.ActionData
import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.action.InteractionMode
import com.andropilot.core.action.PendingConfirmation
import com.andropilot.core.action.UiCondition
import com.andropilot.core.driver.DriverErrorKind
import com.andropilot.core.driver.DriverException
import com.andropilot.core.driver.DriverOutcome
import com.andropilot.core.driver.GesturePlanner
import com.andropilot.core.driver.UiDriver
import com.andropilot.core.model.UiDiff
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.observe.ActionTrace
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.TraceEntry
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.safety.PolicyDecision
import com.andropilot.core.selector.ElementMatcher
import com.andropilot.core.selector.MatchCandidate
import com.andropilot.core.selector.MatchResult
import com.andropilot.core.selector.Selector
import com.andropilot.core.selector.TextScoring
import com.andropilot.core.vision.PerceptionFusion
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * The reference [AndroPilotSession]: orchestration, verification, retries, safety and
 * tracing, on top of an arbitrary [UiDriver].
 *
 * This class contains the behaviour that makes automation *reliable* rather than merely
 * possible, and it contains no Android code, so all of it is exercised by unit tests
 * against a fake driver.
 *
 * Concurrency: a single [Mutex] serializes action execution. The screen is a shared mutable
 * resource; overlapping actions on it produce results no caller can reason about.
 */
public class DefaultAndroPilotSession(
    private val driver: UiDriver,
    private val config: SessionConfig = SessionConfig.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroPilotSession {

    private val mutex = Mutex()
    private val snapshotCounter = AtomicLong(0)
    private val trace = ActionTrace(config.traceCapacity, config.logger, config.redactor)

    private val _snapshots = MutableSharedFlow<UiSnapshot>(replay = 1, extraBufferCapacity = 16)
    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 64)

    @Volatile private var latestSnapshot: UiSnapshot? = null
    @Volatile private var closed = false

    private val pending = LinkedHashMap<String, PendingConfirmation>()
    private val approved = HashSet<String>()

    override val snapshots: SharedFlow<UiSnapshot> get() = _snapshots.asSharedFlow()
    override val results: SharedFlow<ActionResult> get() = _results.asSharedFlow()

    override val isReady: Boolean get() = !closed && driver.isConnected

    override fun readinessProblem(): String? = when {
        closed -> "The session has been closed."
        else -> driver.connectionProblem()
    }

    override fun lastSnapshot(): UiSnapshot? = latestSnapshot

    override fun trace(): List<TraceEntry> = trace.snapshot()

    override fun pendingConfirmations(): List<PendingConfirmation> =
        synchronized(pending) { pending.values.toList() }

    override suspend fun resolveConfirmation(id: String, outcome: ConfirmationOutcome) {
        synchronized(pending) {
            pending.remove(id) ?: return
            if (outcome == ConfirmationOutcome.APPROVED) approved += id
        }
        log(LogLevel.INFO, "Confirmation $id resolved: $outcome")
    }

    override suspend fun close() {
        closed = true
        synchronized(pending) { pending.clear(); approved.clear() }
    }

    override suspend fun find(selector: Selector): MatchResult {
        val snapshot = mutex.withLock { captureAndPublish(includeVisual = false) }
        return ElementMatcher.find(snapshot, selector)
    }

    override suspend fun executeAll(
        actions: List<AgentAction>,
        continueOnFailure: Boolean,
    ): List<ActionResult> {
        val out = ArrayList<ActionResult>(actions.size)
        for (action in actions) {
            val result = execute(action)
            out += result
            if (!result.isSuccess && !continueOnFailure) break
        }
        return out
    }

    override suspend fun execute(action: AgentAction): ActionResult {
        val startedAt = clock()
        val result = try {
            mutex.withLock { dispatch(action, startedAt) }
        } catch (e: DriverException) {
            fail(action, startedAt, e.kind.toFailureReason(), e.message ?: e.kind.name)
        } catch (e: TimeoutCancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log(LogLevel.ERROR, "Unhandled error in ${action.name}", e)
            fail(action, startedAt, FailureReason.INTERNAL_ERROR, e.message ?: e::class.simpleName.orEmpty())
        }
        trace.record(result, startedAt)
        _results.tryEmit(result)
        return result
    }

    // ---------------------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------------------

    private suspend fun dispatch(action: AgentAction, startedAt: Long): ActionResult {
        if (closed) {
            return fail(action, startedAt, FailureReason.NOT_CONNECTED, "The session is closed.")
        }
        if (!driver.isConnected) {
            return fail(
                action, startedAt,
                FailureReason.PERMISSION_REQUIRED,
                driver.connectionProblem() ?: "The SDK is not connected to a device.",
                recommendation = "Enable the AndroPilot accessibility service in system settings.",
            )
        }

        return when (action) {
            is AgentAction.Observe -> doObserve(action, startedAt)
            is AgentAction.Screenshot -> doScreenshot(action, startedAt)
            is AgentAction.FindElement -> doFindElement(action, startedAt)
            is AgentAction.ElementExists -> doElementExists(action, startedAt)
            is AgentAction.Click -> doClick(action, startedAt)
            is AgentAction.ClickPoint -> doClickPoint(action, startedAt)
            is AgentAction.LongPress -> doLongPress(action, startedAt)
            is AgentAction.TypeText -> doTypeText(action, startedAt)
            is AgentAction.ClearText -> doTypeText(
                AgentAction.TypeText(action.selector, "", replace = true),
                startedAt,
                reportAs = action,
            )
            is AgentAction.Scroll -> doScroll(action, startedAt)
            is AgentAction.ScrollUntil -> doScrollUntil(action, startedAt)
            is AgentAction.Swipe -> doSwipe(action, startedAt)
            is AgentAction.PressKey -> doPressKey(action, startedAt)
            is AgentAction.LaunchApp -> doLaunchApp(action, startedAt)
            is AgentAction.OpenIntent -> doOpenIntent(action, startedAt)
            is AgentAction.WaitFor -> doWaitFor(action, startedAt)
            is AgentAction.Sleep -> {
                delay(action.durationMs.coerceIn(0, 60_000))
                ok(action, startedAt, InteractionMode.NONE)
            }
            is AgentAction.Verify -> doVerify(action, startedAt)
        }
    }

    // ---- Perception -------------------------------------------------------------------

    private suspend fun doObserve(action: AgentAction.Observe, startedAt: Long): ActionResult {
        if (action.waitForSettle) awaitSettled(config.settleTimeoutMs)
        val snapshot = captureAndPublish(includeVisual = action.includeVisual)
        val reason = if (snapshot.elements.isEmpty()) {
            FailureReason.NO_PERCEPTION
        } else {
            null
        }
        return if (reason != null) {
            fail(
                action, startedAt, reason,
                "The active window exposed no accessibility nodes.",
                snapshot = snapshot,
                recommendation = if (config.visionProvider == null) {
                    "Register a VisionProvider so the SDK can fall back to visual perception."
                } else {
                    "The vision provider also returned nothing; the screen may be secure (FLAG_SECURE)."
                },
            )
        } else {
            ok(
                action, startedAt, InteractionMode.NONE,
                snapshot = snapshot,
                data = ActionData.SnapshotData(snapshot),
                warnings = snapshot.warnings,
            )
        }
    }

    private suspend fun doScreenshot(action: AgentAction.Screenshot, startedAt: Long): ActionResult {
        val shot = driver.screenshot()
        val encoded = java.util.Base64.getEncoder().encodeToString(shot.pngBytes)
        return ok(
            action, startedAt, InteractionMode.NONE,
            data = ActionData.ScreenshotData(encoded, shot.width, shot.height),
        )
    }

    private suspend fun doFindElement(action: AgentAction.FindElement, startedAt: Long): ActionResult {
        val snapshot = captureAndPublish(includeVisual = shouldUseVision())
        return when (val m = ElementMatcher.find(snapshot, action.selector)) {
            is MatchResult.Matched -> ok(
                action, startedAt, InteractionMode.NONE,
                target = m.candidate.element,
                matchReason = m.candidate.reason,
                snapshot = snapshot,
                data = ActionData.Element(m.candidate.element, m.candidate.score, m.runnersUp),
            )
            is MatchResult.Ambiguous -> ambiguous(action, startedAt, action.selector, m.candidates, snapshot)
            is MatchResult.NotFound -> notFound(action, startedAt, action.selector, m.nearMisses, snapshot)
        }
    }

    private suspend fun doElementExists(action: AgentAction.ElementExists, startedAt: Long): ActionResult {
        val snapshot = captureAndPublish(includeVisual = shouldUseVision())
        val found = ElementMatcher.find(snapshot, action.selector) is MatchResult.Matched
        return ok(
            action, startedAt, InteractionMode.NONE,
            snapshot = snapshot,
            data = ActionData.BooleanValue(found),
        )
    }

    // ---- Interaction ------------------------------------------------------------------

    private suspend fun doClick(action: AgentAction.Click, startedAt: Long): ActionResult =
        withTargetedRetry(action, startedAt, action.selector) { snapshot, target ->
            val warnings = ArrayList<String>(1)
            var mode = InteractionMode.SEMANTIC

            // A visually-detected element has no accessibility node behind it, so the
            // semantic path is skipped rather than attempted and failed: asking the driver
            // to resolve an id it never issued would surface as a stale node, which the
            // fallback rule (correctly) refuses to paper over with a coordinate tap.
            var outcome: DriverOutcome = when {
                target.source == com.andropilot.core.model.PerceptionSource.VISUAL ->
                    DriverOutcome.Rejected(
                        DriverErrorKind.UNSUPPORTED,
                        "The element was detected visually and has no accessibility node.",
                    )
                target.clickable -> driver.performClick(snapshot, target.id)
                else -> DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    "The element does not advertise a click action.",
                )
            }

            // A stale node is deliberately excluded from the fallback: its recorded bounds
            // describe a view that no longer exists, so tapping them would hit whatever has
            // since taken that position. Re-observing and retrying is the only safe answer.
            if (outcome.isFallbackWorthy() && action.allowGestureFallback) {
                val safeArea = driver.gestureSafeArea()
                val point = GesturePlanner.tapPointFor(target.bounds, safeArea)
                if (point != null) {
                    warnings += "The accessibility click was unavailable or rejected; " +
                        "fell back to a synthesized tap at ${point.x},${point.y}."
                    mode = if (target.source == com.andropilot.core.model.PerceptionSource.VISUAL) {
                        InteractionMode.VISUAL
                    } else {
                        InteractionMode.GESTURE
                    }
                    outcome = driver.tap(point)
                }
            }
            InteractionAttempt(outcome, mode, warnings)
        }

    private suspend fun doClickPoint(action: AgentAction.ClickPoint, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val decision = config.policy.evaluate(action, before, null)
        guard(action, startedAt, decision)?.let { return it }

        val safeArea = driver.gestureSafeArea()
        if (action.point !in safeArea) {
            return fail(
                action, startedAt, FailureReason.INVALID_REQUEST,
                "Point ${action.point.x},${action.point.y} is outside the gesture-safe area $safeArea.",
                snapshot = before,
            )
        }
        val warnings = buildList {
            if (before.interactiveElements.isNotEmpty()) {
                add(
                    "A coordinate tap was used although ${before.interactiveElements.size} " +
                        "semantic elements were available; prefer a selector-based click.",
                )
            }
        }
        val outcome = driver.tap(action.point)
        return finishInteraction(
            action, startedAt, before, null,
            InteractionAttempt(outcome, InteractionMode.GESTURE, warnings), null,
        )
    }

    private suspend fun doLongPress(action: AgentAction.LongPress, startedAt: Long): ActionResult =
        withTargetedRetry(action, startedAt, action.selector) { snapshot, target ->
            var mode = InteractionMode.SEMANTIC
            val warnings = ArrayList<String>(1)
            var outcome: DriverOutcome = if (target.longClickable) {
                driver.performLongClick(snapshot, target.id)
            } else {
                DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    "The element does not advertise a long-click action.",
                )
            }
            if (outcome.isFallbackWorthy()) {
                val point = GesturePlanner.tapPointFor(target.bounds, driver.gestureSafeArea())
                if (point != null) {
                    warnings += "Fell back to a synthesized long press."
                    mode = InteractionMode.GESTURE
                    outcome = driver.longPress(point, action.durationMs.coerceIn(200, 10_000))
                }
            }
            InteractionAttempt(outcome, mode, warnings)
        }

    private suspend fun doTypeText(
        action: AgentAction.TypeText,
        startedAt: Long,
        reportAs: AgentAction = action,
    ): ActionResult {
        val selector = action.selector?.copy(editable = true, visibleOnly = true)
        return withTargetedRetry(
            reportAs, startedAt, selector,
            resolveTarget = { snapshot ->
                if (selector != null) null else snapshot.focusedElement?.takeIf { it.editable }
                    ?: snapshot.editableElements.singleOrNull()
            },
            noTargetMessage = "No text field was specified and none is focused. " +
                "Pass a selector, or click the field first.",
            policyAction = action,
        ) { snapshot, target ->
            val warnings = ArrayList<String>(2)
            if (!target.focused) {
                val focusOutcome = driver.focus(snapshot, target.id)
                if (focusOutcome !is DriverOutcome.Ok) {
                    warnings += "The field could not be focused before typing."
                }
            }
            val finalText = if (action.replace) {
                action.text
            } else {
                (target.text ?: "") + action.text
            }
            var outcome = driver.setText(snapshot, target.id, finalText)
            if (outcome is DriverOutcome.Ok && action.pressImeAction) {
                val ime = driver.performImeAction()
                if (ime !is DriverOutcome.Ok) warnings += "The IME action could not be performed."
            }
            if (outcome !is DriverOutcome.Ok) {
                outcome = DriverOutcome.Rejected(
                    DriverErrorKind.ACTION_REJECTED,
                    (outcome as DriverOutcome.Rejected).message,
                )
            }
            InteractionAttempt(outcome, InteractionMode.SEMANTIC, warnings)
        }
    }

    private suspend fun doScroll(action: AgentAction.Scroll, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val container = resolveScrollContainer(before, action.selector)
            ?: return fail(
                action, startedAt, FailureReason.ELEMENT_NOT_FOUND,
                "No scrollable container is visible on this screen.",
                snapshot = before,
                recommendation = "Observe the screen and check `scrollableContainers` before scrolling.",
            )

        val decision = config.policy.evaluate(action, before, container)
        guard(action, startedAt, decision)?.let { return it }

        val warnings = ArrayList<String>(1)
        var mode = InteractionMode.SEMANTIC
        var outcome: DriverOutcome = DriverOutcome.Rejected(DriverErrorKind.ACTION_REJECTED, "not attempted")
        var lastSeen = before

        repeat(action.steps.coerceIn(1, 20)) { step ->
            if (step > 0) lastSeen = captureAndPublish(includeVisual = false)
            outcome = driver.performScroll(lastSeen, container.id, action.direction)
            if (outcome !is DriverOutcome.Ok) {
                val path = GesturePlanner.planScroll(
                    container = container.bounds,
                    safeArea = driver.gestureSafeArea(),
                    direction = action.direction,
                    amount = action.amount,
                )
                if (path == null) {
                    outcome = DriverOutcome.Rejected(
                        DriverErrorKind.ACTION_REJECTED,
                        "The container is too small to scroll by gesture.",
                    )
                    return@repeat
                }
                mode = InteractionMode.GESTURE
                if (step == 0) warnings += "Used a synthesized swipe; the container refused ACTION_SCROLL."
                outcome = driver.swipe(path.start, path.end, path.durationMs)
            }
            if (outcome !is DriverOutcome.Ok) return@repeat
        }

        return finishInteraction(
            action, startedAt, before, container,
            InteractionAttempt(outcome, mode, warnings), null,
            // Scrolling to the end of a list legitimately produces no change.
            noEffectIsFailure = false,
        )
    }

    private suspend fun doScrollUntil(action: AgentAction.ScrollUntil, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        if (evaluate(action.until, before)) {
            return ok(action, startedAt, InteractionMode.NONE, snapshot = before, diff = UiDiff.NONE)
        }
        var previous = before
        val steps = action.maxSteps.coerceIn(1, 50)
        for (i in 0 until steps) {
            val step = execute0(AgentAction.Scroll(action.direction, action.selector))
            if (step is ActionResult.Failure && step.reason != FailureReason.NO_EFFECT) {
                return fail(
                    action, startedAt, step.reason,
                    "Scrolling stopped after $i step(s): ${step.message}",
                    snapshot = previous,
                )
            }
            awaitSettled(config.settleTimeoutMs)
            val current = captureAndPublish(includeVisual = false)
            if (evaluate(action.until, current)) {
                return ok(
                    action, startedAt, InteractionMode.GESTURE,
                    snapshot = current,
                    diff = UiDiff.between(before, current),
                )
            }
            val moved = UiDiff.between(previous, current)
            if (!moved.changed) {
                return fail(
                    action, startedAt, FailureReason.TIMEOUT,
                    "The condition was not met and the content stopped moving after ${i + 1} step(s); " +
                        "the list has probably reached its end.",
                    snapshot = current,
                    recommendation = "Try the opposite direction, or check the condition is reachable here.",
                )
            }
            previous = current
        }
        return fail(
            action, startedAt, FailureReason.TIMEOUT,
            "The condition was not met within $steps scroll step(s).",
            snapshot = previous,
        )
    }

    private suspend fun doSwipe(action: AgentAction.Swipe, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val decision = config.policy.evaluate(action, before, null)
        guard(action, startedAt, decision)?.let { return it }

        val path = GesturePlanner.sanitizeSwipe(
            com.andropilot.core.driver.GesturePath(action.start, action.end, action.durationMs),
            driver.gestureSafeArea(),
        ) ?: return fail(
            action, startedAt, FailureReason.INVALID_REQUEST,
            "The swipe is shorter than the minimum recognisable travel " +
                "(${GesturePlanner.MIN_TRAVEL_PX}px) once clamped to the safe area.",
            snapshot = before,
        )

        val outcome = driver.swipe(path.start, path.end, path.durationMs)
        return finishInteraction(
            action, startedAt, before, null,
            InteractionAttempt(outcome, InteractionMode.GESTURE, emptyList()), null,
            noEffectIsFailure = false,
        )
    }

    private suspend fun doPressKey(action: AgentAction.PressKey, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val decision = config.policy.evaluate(action, before, null)
        guard(action, startedAt, decision)?.let { return it }
        val outcome = driver.pressKey(action.key)
        return finishInteraction(
            action, startedAt, before, null,
            InteractionAttempt(outcome, InteractionMode.SYSTEM, emptyList()), null,
        )
    }

    private suspend fun doLaunchApp(action: AgentAction.LaunchApp, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val decision = config.policy.evaluate(action, before, null)
        guard(action, startedAt, decision)?.let { return it }

        when (val outcome = driver.launchApp(action.packageName)) {
            is DriverOutcome.Ok -> Unit
            is DriverOutcome.Rejected -> return fail(
                action, startedAt, outcome.kind.toFailureReason(), outcome.message,
                snapshot = before,
                recommendation = if (outcome.kind == DriverErrorKind.APP_UNAVAILABLE) {
                    "Check the package name; the app may not be installed or may have no launcher activity."
                } else {
                    null
                },
            )
        }

        if (action.waitForForeground) {
            val reached = awaitCondition(
                UiCondition.PackageInForeground(action.packageName),
                timeoutMs = maxOf(config.defaultWaitTimeoutMs, 5_000),
            )
            if (!reached) {
                return fail(
                    action, startedAt, FailureReason.TIMEOUT,
                    "'${action.packageName}' did not reach the foreground in time.",
                    snapshot = latestSnapshot,
                    recommendation = "The app may be showing a splash screen; retry with a longer timeout.",
                )
            }
        }
        awaitSettled(config.settleTimeoutMs)
        val after = captureAndPublish(includeVisual = false)
        return ok(
            action, startedAt, InteractionMode.SYSTEM,
            snapshot = after, diff = UiDiff.between(before, after),
        )
    }

    private suspend fun doOpenIntent(action: AgentAction.OpenIntent, startedAt: Long): ActionResult {
        val before = captureAndPublish(includeVisual = false)
        val decision = config.policy.evaluate(action, before, null)
        guard(action, startedAt, decision)?.let { return it }
        val outcome = driver.openIntent(action.action, action.uri, action.packageName, action.extras)
        return finishInteraction(
            action, startedAt, before, null,
            InteractionAttempt(outcome, InteractionMode.SYSTEM, emptyList()), null,
        )
    }

    // ---- Synchronisation --------------------------------------------------------------

    private suspend fun doWaitFor(action: AgentAction.WaitFor, startedAt: Long): ActionResult {
        val met = awaitCondition(action.condition, action.timeoutMs, action.pollIntervalMs)
        val snapshot = latestSnapshot
        return if (met) {
            ok(action, startedAt, InteractionMode.NONE, snapshot = snapshot)
        } else {
            fail(
                action, startedAt, FailureReason.TIMEOUT,
                "The condition was not met within ${action.timeoutMs}ms.",
                snapshot = snapshot,
                recommendation = describeUnmet(action.condition, snapshot),
            )
        }
    }

    private suspend fun doVerify(action: AgentAction.Verify, startedAt: Long): ActionResult {
        val met = awaitCondition(action.condition, action.timeoutMs)
        val snapshot = latestSnapshot
        return if (met) {
            ok(
                action, startedAt, InteractionMode.NONE,
                snapshot = snapshot, data = ActionData.BooleanValue(true),
            )
        } else {
            fail(
                action, startedAt, FailureReason.TIMEOUT,
                "Verification failed: the expected UI state was not reached.",
                snapshot = snapshot,
                recommendation = describeUnmet(action.condition, snapshot),
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Shared interaction machinery
    // ---------------------------------------------------------------------------------

    private class InteractionAttempt(
        val outcome: DriverOutcome,
        val mode: InteractionMode,
        val warnings: List<String>,
    )

    /**
     * The common path for every action that targets an element.
     *
     * Resolves the selector against a *fresh* snapshot each attempt -- never against a
     * cached one -- because the whole class of "stale element" bugs comes from acting on
     * geometry that has since moved. Transient failures are retried with backoff.
     */
    private suspend fun withTargetedRetry(
        reportedAction: AgentAction,
        startedAt: Long,
        selector: Selector?,
        resolveTarget: (UiSnapshot) -> UiElement? = { null },
        noTargetMessage: String = "No selector was supplied.",
        policyAction: AgentAction = reportedAction,
        interact: suspend (UiSnapshot, UiElement) -> InteractionAttempt,
    ): ActionResult {
        var attempt = 0
        var lastFailure: ActionResult.Failure? = null

        while (attempt <= config.maxRetries) {
            if (attempt > 0) {
                delay(config.retryBackoffMs shl (attempt - 1))
                log(LogLevel.DEBUG, "Retrying ${reportedAction.name} (attempt ${attempt + 1})")
            }
            attempt++

            val before = captureAndPublish(includeVisual = shouldUseVision())
            val target: UiElement
            if (selector != null) {
                when (val m = ElementMatcher.find(before, selector)) {
                    is MatchResult.Matched -> target = m.candidate.element
                    is MatchResult.Ambiguous -> {
                        // Ambiguity is a property of the screen, not a timing fluke.
                        return ambiguous(reportedAction, startedAt, selector, m.candidates, before)
                    }
                    is MatchResult.NotFound -> {
                        lastFailure = notFound(reportedAction, startedAt, selector, m.nearMisses, before)
                        continue
                    }
                }
            } else {
                target = resolveTarget(before) ?: return fail(
                    reportedAction, startedAt, FailureReason.INVALID_REQUEST,
                    noTargetMessage, snapshot = before,
                )
            }

            if (!target.isActionable && target.bounds.isEmpty) {
                lastFailure = fail(
                    reportedAction, startedAt, FailureReason.ELEMENT_NOT_ACTIONABLE,
                    "'${target.describe()}' has no usable bounds.",
                    snapshot = before,
                )
                continue
            }
            if (!target.enabled) {
                return fail(
                    reportedAction, startedAt, FailureReason.ELEMENT_NOT_ACTIONABLE,
                    "'${target.describe()}' is disabled.",
                    snapshot = before,
                    recommendation = "Satisfy the app's preconditions (required fields, permissions) first.",
                )
            }

            val decision = config.policy.evaluate(policyAction, before, target)
            guard(reportedAction, startedAt, decision, target)?.let { return it }

            val attemptResult = interact(before, target)
            val settled = finishInteraction(
                reportedAction, startedAt, before, target, attemptResult,
                matchReason = null,
            )
            if (settled is ActionResult.Success) return settled
            lastFailure = settled as ActionResult.Failure
            if (!lastFailure.isTransient) return lastFailure
        }
        return lastFailure ?: fail(
            reportedAction, startedAt, FailureReason.INTERNAL_ERROR,
            "The action exhausted its retries without producing a result.",
        )
    }

    /** Waits for the UI to settle, diffs, and decides whether the action really worked. */
    private suspend fun finishInteraction(
        action: AgentAction,
        startedAt: Long,
        before: UiSnapshot,
        target: UiElement?,
        attempt: InteractionAttempt,
        matchReason: String?,
        noEffectIsFailure: Boolean = config.treatNoEffectAsFailure,
    ): ActionResult {
        when (val outcome = attempt.outcome) {
            is DriverOutcome.Ok -> Unit
            is DriverOutcome.Rejected -> return fail(
                action, startedAt, outcome.kind.toFailureReason(), outcome.message,
                snapshot = before,
                recommendation = when (outcome.kind) {
                    DriverErrorKind.STALE_NODE ->
                        "The UI changed between observation and dispatch; observe again and retry."
                    DriverErrorKind.ACTION_REJECTED ->
                        "The app refused the action; a gesture fallback or a different target may work."
                    else -> null
                },
            )
        }

        if (config.postActionDelayMs > 0) delay(config.postActionDelayMs)
        awaitSettled(config.settleTimeoutMs)
        val after = captureAndPublish(includeVisual = false)
        val diff = UiDiff.between(before, after)

        if (!diff.changed && noEffectIsFailure) {
            return fail(
                action, startedAt, FailureReason.NO_EFFECT,
                "The action was dispatched successfully but the UI did not change in any " +
                    "way the SDK could observe.",
                snapshot = after,
                recommendation = "The target may be decorative, or the effect may be off-screen. " +
                    "Check the intended element, or set treatNoEffectAsFailure = false if the " +
                    "app legitimately shows no feedback.",
            )
        }

        return ok(
            action, startedAt, attempt.mode,
            target = target,
            matchReason = matchReason,
            snapshot = after,
            diff = diff,
            warnings = attempt.warnings,
        )
    }

    /** Re-enters dispatch for a composed sub-action without re-acquiring the mutex. */
    private suspend fun execute0(action: AgentAction): ActionResult =
        dispatch(action, clock())

    // ---------------------------------------------------------------------------------
    // Perception plumbing
    // ---------------------------------------------------------------------------------

    private suspend fun captureAndPublish(includeVisual: Boolean): UiSnapshot {
        val raw = driver.captureSnapshot()
        val capped = capElements(raw)
        val enriched = if (includeVisual) applyVision(capped) else capped
        val stamped = enriched.copy(snapshotId = "s${snapshotCounter.incrementAndGet()}")
        latestSnapshot = stamped
        _snapshots.tryEmit(stamped)
        return stamped
    }

    /**
     * Bounds snapshot size. Deep, unlabelled, non-interactive nodes are dropped first,
     * because they are exactly the ones that cost tokens and tell an agent nothing.
     */
    private fun capElements(snapshot: UiSnapshot): UiSnapshot {
        if (snapshot.elements.size <= config.maxElements) return snapshot
        val keep = snapshot.elements
            .sortedWith(
                compareByDescending<UiElement> { it.isActionable }
                    .thenByDescending { it.label != null }
                    .thenBy { it.depth },
            )
            .take(config.maxElements)
            .map { it.id }
            .toSet()
        val filtered = snapshot.elements
            .filter { it.id in keep }
            .map { e -> e.copy(childIds = e.childIds.filter { it in keep }) }
        return snapshot.copy(
            elements = filtered,
            warnings = snapshot.warnings +
                "The hierarchy was truncated from ${snapshot.elements.size} to ${filtered.size} elements.",
        )
    }

    private suspend fun applyVision(snapshot: UiSnapshot): UiSnapshot {
        val provider = config.visionProvider ?: return snapshot
        if (PerceptionFusion.isSemanticPerceptionSufficient(snapshot, config.vision)) return snapshot
        return try {
            val shot = driver.screenshot()
            val detections = withTimeoutOrNull(config.vision.timeoutMs) {
                provider.analyze(shot, snapshot.elements)
            }
            if (detections == null) {
                log(LogLevel.WARN, "The vision provider timed out after ${config.vision.timeoutMs}ms.")
                snapshot.copy(warnings = snapshot.warnings + "Visual perception timed out.")
            } else {
                log(LogLevel.DEBUG, "Vision contributed ${detections.size} detection(s).")
                PerceptionFusion.fuse(snapshot, detections, config.vision)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(LogLevel.WARN, "The vision provider failed; continuing semantically.", e)
            snapshot.copy(warnings = snapshot.warnings + "Visual perception failed: ${e.message}")
        }
    }

    private fun shouldUseVision(): Boolean = config.visionProvider != null

    /**
     * Picks the container a scroll should target.
     *
     * With no selector, the largest visible scrollable wins: on a real screen that is the
     * content list, not the tab strip or a nested carousel. When a selector is given but
     * resolves to a non-scrollable element, the nearest scrollable ancestor is used, because
     * an agent naturally says "scroll the list of results" while pointing at a row.
     */
    private fun resolveScrollContainer(snapshot: UiSnapshot, selector: Selector?): UiElement? {
        if (selector == null) return snapshot.primaryScrollable
        val matched = (ElementMatcher.find(snapshot, selector) as? MatchResult.Matched)
            ?.candidate?.element
            ?: return snapshot.primaryScrollable
        if (matched.scrollable) return matched
        return snapshot.ancestorsOf(matched).firstOrNull { it.scrollable }
            ?: snapshot.descendantsOf(matched).filter { it.scrollable }.maxByOrNull { it.bounds.area }
            ?: snapshot.primaryScrollable
    }

    // ---------------------------------------------------------------------------------
    // Conditions & settling
    // ---------------------------------------------------------------------------------

    /**
     * Waits until the structural signature stops changing.
     *
     * Signature-based rather than time-based: a fixed sleep is either too short (flaky) or
     * too long (slow), and neither adapts to the device. Non-interactive churn is excluded
     * from the signature, so a ticking clock does not keep the screen "unsettled" forever.
     */
    private suspend fun awaitSettled(timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        var lastSignature: String? = null
        var stableSince = clock()
        while (true) {
            val snapshot = driver.captureSnapshot()
            val signature = snapshot.structuralSignature()
            val now = clock()
            if (signature != lastSignature) {
                lastSignature = signature
                stableSince = now
            } else if (now - stableSince >= config.settleQuietPeriodMs) {
                return true
            }
            if (now >= deadline) {
                log(LogLevel.DEBUG, "The UI did not settle within ${timeoutMs}ms; proceeding anyway.")
                return false
            }
            delay(config.pollIntervalMs)
        }
    }

    private suspend fun awaitCondition(
        condition: UiCondition,
        timeoutMs: Long,
        pollIntervalMs: Long = config.pollIntervalMs,
    ): Boolean {
        val deadline = clock() + timeoutMs.coerceAtLeast(0)
        while (true) {
            val snapshot = captureAndPublish(includeVisual = false)
            if (evaluate(condition, snapshot)) return true
            if (clock() >= deadline) return false
            delay(pollIntervalMs.coerceIn(20, 2_000))
        }
    }

    private suspend fun evaluate(condition: UiCondition, snapshot: UiSnapshot): Boolean =
        when (condition) {
            is UiCondition.ElementPresent ->
                ElementMatcher.find(snapshot, condition.selector) is MatchResult.Matched
            is UiCondition.ElementAbsent ->
                ElementMatcher.find(snapshot, condition.selector) !is MatchResult.Matched
            is UiCondition.PackageInForeground -> snapshot.packageName == condition.packageName
            is UiCondition.TextVisible -> snapshot.elements.any { e ->
                e.visible && e.label?.let { TextScoring.similarity(condition.text, it) >= 0.8 } == true
            }
            is UiCondition.UiSettled -> awaitSettled(condition.stableForMs)
            is UiCondition.AllOf -> condition.conditions.all { evaluate(it, snapshot) }
            is UiCondition.AnyOf -> condition.conditions.any { evaluate(it, snapshot) }
        }

    private fun describeUnmet(condition: UiCondition, snapshot: UiSnapshot?): String =
        when (condition) {
            is UiCondition.ElementPresent ->
                "Nothing matched ${condition.selector.describe()}. " +
                    "Visible labels: ${snapshot?.visibleText?.take(8)?.joinToString() ?: "none"}."
            is UiCondition.PackageInForeground ->
                "The foreground app is '${snapshot?.packageName}', not '${condition.packageName}'."
            is UiCondition.TextVisible ->
                "'${condition.text}' is not on screen."
            else -> "The condition did not hold before the timeout."
        }

    // ---------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------

    /** Returns a Failure when the action must not proceed, or null to continue. */
    private fun guard(
        action: AgentAction,
        startedAt: Long,
        decision: PolicyDecision,
        target: UiElement? = null,
    ): ActionResult.Failure? = when (decision) {
        is PolicyDecision.Allow -> null
        is PolicyDecision.Deny -> fail(
            action, startedAt, FailureReason.BLOCKED_BY_POLICY, decision.reason,
        )
        is PolicyDecision.RequireConfirmation -> {
            val key = confirmationKey(action, target)
            val alreadyApproved = synchronized(pending) { approved.remove(key) }
            if (alreadyApproved) {
                null
            } else {
                val confirmation = PendingConfirmation(
                    id = key,
                    action = action,
                    risk = decision.risk,
                    description = describeForHuman(action, target),
                    reasons = decision.reasons,
                )
                synchronized(pending) { pending[key] = confirmation }
                fail(
                    action, startedAt, FailureReason.CONFIRMATION_REQUIRED,
                    "This action is classified ${decision.risk.name.lowercase()} and needs confirmation.",
                    pendingConfirmation = confirmation,
                    recommendation = "Call resolveConfirmation(\"$key\", APPROVED) then repeat the action.",
                )
            }
        }
    }

    /**
     * Confirmation identity: the same action on the same target reuses an approval, a
     * *different* one does not. Approvals are single-use so an agent cannot get one blanket
     * "yes" and then send a hundred messages.
     */
    private fun confirmationKey(action: AgentAction, target: UiElement?): String {
        val targetPart = target?.let { "${it.role}:${it.label.orEmpty()}:${it.resourceId.orEmpty()}" } ?: "-"
        val actionPart = when (action) {
            is AgentAction.TypeText -> "type:${action.text.length}:${action.sensitive}"
            is AgentAction.OpenIntent -> "intent:${action.action}:${action.uri}"
            is AgentAction.LaunchApp -> "launch:${action.packageName}"
            else -> action.name
        }
        // Deterministic on purpose: the approval an agent obtained must be findable when it
        // repeats the same action on the same target. Single use comes from removing the
        // key on redemption, not from making it unguessable.
        return "confirm-" + Integer.toHexString((actionPart + '|' + targetPart).hashCode())
    }

    private fun describeForHuman(action: AgentAction, target: UiElement?): String = when (action) {
        is AgentAction.Click -> "Tap \"${target?.label ?: action.selector.describe()}\""
        is AgentAction.LongPress -> "Long-press \"${target?.label ?: action.selector.describe()}\""
        is AgentAction.ClickPoint -> "Tap at ${action.point.x},${action.point.y}"
        is AgentAction.TypeText ->
            if (action.sensitive) "Enter a credential into \"${target?.label ?: "a field"}\""
            else "Enter text into \"${target?.label ?: "a field"}\""
        is AgentAction.LaunchApp -> "Open the app ${action.packageName}"
        is AgentAction.OpenIntent -> "Open ${action.uri ?: action.action}"
        else -> "Perform ${action.name}"
    }

    // ---------------------------------------------------------------------------------
    // Result construction
    // ---------------------------------------------------------------------------------

    private fun ok(
        action: AgentAction,
        startedAt: Long,
        mode: InteractionMode,
        target: UiElement? = null,
        matchReason: String? = null,
        snapshot: UiSnapshot? = null,
        diff: UiDiff? = null,
        data: ActionData? = null,
        warnings: List<String> = emptyList(),
    ) = ActionResult.Success(
        action = action,
        durationMs = clock() - startedAt,
        interactionMode = mode,
        target = target,
        matchReason = matchReason,
        snapshot = snapshot,
        diff = diff,
        data = data,
        warnings = warnings,
    )

    private fun fail(
        action: AgentAction,
        startedAt: Long,
        reason: FailureReason,
        message: String,
        candidates: List<MatchCandidate> = emptyList(),
        snapshot: UiSnapshot? = null,
        pendingConfirmation: PendingConfirmation? = null,
        recommendation: String? = null,
    ) = ActionResult.Failure(
        action = action,
        durationMs = clock() - startedAt,
        reason = reason,
        message = message,
        candidates = candidates,
        snapshot = snapshot,
        pendingConfirmation = pendingConfirmation,
        recommendation = recommendation,
    )

    private fun notFound(
        action: AgentAction,
        startedAt: Long,
        selector: Selector,
        nearMisses: List<MatchCandidate>,
        snapshot: UiSnapshot,
    ) = fail(
        action, startedAt, FailureReason.ELEMENT_NOT_FOUND,
        "Nothing on screen matched ${selector.describe()}.",
        candidates = nearMisses,
        snapshot = snapshot,
        recommendation = when {
            nearMisses.isNotEmpty() ->
                "The closest match was \"${nearMisses.first().element.describe()}\" " +
                    "(score ${nearMisses.first().score}); lower minScore or refine the selector."
            snapshot.primaryScrollable != null ->
                "The screen is scrollable; the element may be below the fold. Try scroll_until."
            else -> "Observe the screen to see what is actually available."
        },
    )

    private fun ambiguous(
        action: AgentAction,
        startedAt: Long,
        selector: Selector,
        candidates: List<MatchCandidate>,
        snapshot: UiSnapshot,
    ) = fail(
        action, startedAt, FailureReason.AMBIGUOUS_TARGET,
        "${candidates.size} elements matched ${selector.describe()} with similar confidence.",
        candidates = candidates,
        snapshot = snapshot,
        recommendation = "Disambiguate with `index`, `region`, `within`, or `near`. " +
            "Candidates: " + candidates.joinToString(" | ") { it.element.describe() },
    )

    private fun log(level: LogLevel, message: String, error: Throwable? = null) {
        if (level.ordinal >= config.logLevel.ordinal) {
            config.logger.log(level, ActionTrace.TAG, message, error)
        }
    }
}

/**
 * Whether a rejection is worth retrying as a synthesized gesture.
 *
 * Only rejections that mean "this node will not perform the action" qualify. A stale node,
 * a missing permission or a lost connection must not be papered over with a coordinate tap.
 */
private fun DriverOutcome.isFallbackWorthy(): Boolean =
    this is DriverOutcome.Rejected &&
        (kind == DriverErrorKind.ACTION_REJECTED || kind == DriverErrorKind.UNSUPPORTED)

private fun DriverErrorKind.toFailureReason(): FailureReason = when (this) {
    DriverErrorKind.NOT_CONNECTED -> FailureReason.NOT_CONNECTED
    DriverErrorKind.PERMISSION_REQUIRED -> FailureReason.PERMISSION_REQUIRED
    DriverErrorKind.STALE_NODE -> FailureReason.STALE_ELEMENT
    DriverErrorKind.ACTION_REJECTED -> FailureReason.DISPATCH_FAILED
    DriverErrorKind.UNSUPPORTED -> FailureReason.UNSUPPORTED
    DriverErrorKind.APP_UNAVAILABLE -> FailureReason.APP_UNAVAILABLE
    DriverErrorKind.TIMEOUT -> FailureReason.TIMEOUT
    DriverErrorKind.INTERNAL -> FailureReason.INTERNAL_ERROR
}
