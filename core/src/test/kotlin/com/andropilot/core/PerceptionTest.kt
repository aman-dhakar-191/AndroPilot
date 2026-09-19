package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.Direction
import com.andropilot.core.driver.GesturePlanner
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.Orientation
import com.andropilot.core.model.PerceptionSource
import com.andropilot.core.model.Point
import com.andropilot.core.model.ScreenMetrics
import com.andropilot.core.model.UiDiff
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import com.andropilot.core.vision.PerceptionFusion
import com.andropilot.core.vision.VisionConfig
import com.andropilot.core.vision.VisionProvider
import com.andropilot.core.vision.VisualElement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class PerceptionTest {

    @Nested
    inner class Geometry {

        @Test
        fun `iou is 1 for identical rectangles and 0 for disjoint ones`() {
            val a = Bounds(0, 0, 100, 100)
            assertEquals(1.0, a.iou(a))
            assertEquals(0.0, a.iou(Bounds(200, 200, 300, 300)))
        }

        @Test
        fun `iou is symmetric and partial for overlap`() {
            val a = Bounds(0, 0, 100, 100)
            val b = Bounds(50, 0, 150, 100)
            assertEquals(a.iou(b), b.iou(a))
            assertTrue(a.iou(b) in 0.3..0.4)
        }

        @Test
        fun `the safe frame excludes system insets`() {
            val metrics = ScreenMetrics(
                1080, 1920, 3f, Orientation.PORTRAIT,
                com.andropilot.core.model.Insets(top = 72, bottom = 120),
            )
            assertEquals(Bounds(0, 72, 1080, 1800), metrics.safeFrame)
        }

        @Test
        fun `density conversion round-trips`() {
            val metrics = ScreenMetrics.DEFAULT
            assertEquals(48, metrics.toPx(metrics.toDp(48)))
        }
    }

    @Nested
    inner class Gestures {

        private val safe = FakeScreens.PHONE.safeFrame

        @Test
        fun `scrolling down moves the finger up`() {
            val path = GesturePlanner.planScroll(Bounds(0, 300, 1080, 1700), safe, Direction.DOWN)!!
            assertTrue(path.end.y < path.start.y)
            assertEquals(path.start.x, path.end.x)
        }

        @Test
        fun `scrolling up moves the finger down`() {
            val path = GesturePlanner.planScroll(Bounds(0, 300, 1080, 1700), safe, Direction.UP)!!
            assertTrue(path.end.y > path.start.y)
        }

        @Test
        fun `a scroll never starts or ends inside the system insets`() {
            val path = GesturePlanner.planScroll(Bounds(0, 0, 1080, 1920), safe, Direction.DOWN)!!
            assertTrue(path.start in safe) { "start ${path.start} escaped $safe" }
            assertTrue(path.end in safe) { "end ${path.end} escaped $safe" }
        }

        @Test
        fun `a scroll keeps clear of the left and right edges where back gestures live`() {
            val guard = (safe.width * GesturePlanner.EDGE_GUARD_FRACTION).toInt()
            val path = GesturePlanner.planScroll(Bounds(0, 300, 1080, 1700), safe, Direction.LEFT)!!
            assertTrue(path.start.x >= safe.left + guard - 1)
            assertTrue(path.end.x <= safe.right - guard + 1)
        }

        @Test
        fun `a container too small to swipe yields no gesture rather than a bad one`() {
            assertNull(GesturePlanner.planScroll(Bounds(0, 500, 1080, 520), safe, Direction.DOWN))
        }

        @Test
        fun `a container entirely outside the safe area yields no gesture`() {
            assertNull(GesturePlanner.planScroll(Bounds(0, 1850, 1080, 1920), safe, Direction.DOWN))
        }

        @Test
        fun `larger amounts travel further`() {
            val container = Bounds(0, 300, 1080, 1700)
            val small = GesturePlanner.planScroll(container, safe, Direction.DOWN, amount = 0.2)!!
            val large = GesturePlanner.planScroll(container, safe, Direction.DOWN, amount = 0.9)!!
            assertTrue(
                (large.start.y - large.end.y) > (small.start.y - small.end.y),
            )
        }

        @Test
        fun `a sub-slop swipe is rejected`() {
            val path = com.andropilot.core.driver.GesturePath(Point(500, 500), Point(505, 505), 100)
            assertNull(GesturePlanner.sanitizeSwipe(path, safe))
        }

        @Test
        fun `tap points are pulled inside the visible area for partially clipped elements`() {
            val point = GesturePlanner.tapPointFor(Bounds(0, 1700, 1080, 2100), safe)!!
            assertTrue(point in safe)
        }
    }

    @Nested
    inner class Diffing {

        @Test
        fun `an identical screen produces no change`() {
            val snapshot = FakeScreens.login().toSnapshot()
            assertFalse(UiDiff.between(snapshot, snapshot).changed)
        }

        @Test
        fun `a different foreground app is reported as an app change`() {
            val diff = UiDiff.between(
                FakeScreens.login().toSnapshot(),
                FakeScreens.launcher().toSnapshot(),
            )
            assertTrue(diff.appChanged)
            assertTrue(diff.summarize().contains("app"))
        }

        @Test
        fun `a toggled switch is reported as an element state change, not a structure change`() {
            val before = FakeScreens.login()
            before.add(com.andropilot.core.testing.Ui.toggle("t", "Notifications", Bounds(60, 1400, 1020, 1500)))
            val a = before.toSnapshot()
            before.update("t") { it.copy(checked = true) }
            val diff = UiDiff.between(a, before.toSnapshot())
            assertTrue(diff.changedElements.any { it.summary.contains("checked") })
        }

        @Test
        fun `a dialog appearing and being dismissed are distinguished`() {
            val plain = FakeScreens.login().toSnapshot()
            val dialog = FakeScreens.confirmDialog().toSnapshot()
            assertTrue(UiDiff.between(plain, dialog).dialogAppeared)
            assertTrue(UiDiff.between(dialog, plain).dialogDismissed)
        }

        @Test
        fun `a consistent vertical shift is recognised as a scroll rather than new content`() {
            val screen = FakeScreens.settingsList()
            val before = screen.toSnapshot()
            screen.simulateScroll(screen.elements.first { it.scrollable }, Direction.DOWN)
            val diff = UiDiff.between(before, screen.toSnapshot())
            assertTrue(diff.scrollDeltaY < 0) { "expected upward movement, got ${diff.scrollDeltaY}" }
            assertTrue(diff.summarize().contains("scroll"))
        }

        @Test
        fun `the structural signature ignores churn in non-interactive text`() {
            val screen = FakeScreens.login()
            val before = screen.toSnapshot().structuralSignature()
            screen.update("title") { it.copy(text = "Welcome back — 12:04") }
            assertEquals(before, screen.toSnapshot().structuralSignature())
        }

        @Test
        fun `the structural signature does change when a button appears`() {
            val screen = FakeScreens.login()
            val before = screen.toSnapshot().structuralSignature()
            screen.add(com.andropilot.core.testing.Ui.button("extra", "Continue", Bounds(60, 1400, 1020, 1500)))
            assertTrue(before != screen.toSnapshot().structuralSignature())
        }
    }

    @Nested
    inner class Vision {

        private val canvas = FakeScreens.opaqueCanvas().toSnapshot()

        @Test
        fun `vision adds elements to a screen with no semantic information`() {
            val fused = PerceptionFusion.fuse(
                canvas,
                listOf(VisualElement(Bounds(100, 900, 500, 1000), "Start game", confidence = 0.9)),
            )
            val added = fused.elements.single { it.source == PerceptionSource.VISUAL }
            assertEquals("Start game", added.text)
            assertTrue(added.clickable)
            assertTrue(fused.warnings.any { it.contains("visually") })
        }

        @Test
        fun `vision enriches an unlabelled icon instead of duplicating it`() {
            val home = FakeScreens.home().toSnapshot()
            val iconBounds = home.require("nav_orders").bounds
            val fused = PerceptionFusion.fuse(
                home,
                listOf(VisualElement(iconBounds, "Orders", confidence = 0.8)),
            )
            assertEquals(home.elements.size, fused.elements.size)
            val enriched = fused.require("nav_orders")
            assertEquals("Orders", enriched.contentDescription)
            assertEquals(PerceptionSource.FUSED, enriched.source)
        }

        @Test
        fun `low-confidence detections are dropped`() {
            val fused = PerceptionFusion.fuse(
                canvas,
                listOf(VisualElement(Bounds(100, 900, 500, 1000), "noise", confidence = 0.05)),
            )
            assertEquals(canvas.elements.size, fused.elements.size)
        }

        @Test
        fun `semantic information always wins over a conflicting detection`() {
            val login = FakeScreens.login().toSnapshot()
            val submitBounds = login.require("submit").bounds
            val fused = PerceptionFusion.fuse(
                login,
                listOf(VisualElement(submitBounds, "Log in", confidence = 0.99)),
            )
            assertEquals("Sign in", fused.require("submit").text)
            assertFalse(fused.elements.any { it.source == PerceptionSource.VISUAL })
        }

        @Test
        fun `a well-described screen skips visual analysis entirely`() {
            assertTrue(
                PerceptionFusion.isSemanticPerceptionSufficient(FakeScreens.login().toSnapshot()),
            )
            assertFalse(PerceptionFusion.isSemanticPerceptionSufficient(canvas))
        }

        @Test
        fun `a session can click an element that only vision found`() = runTest {
            val provider = VisionProvider { _, _ ->
                listOf(VisualElement(Bounds(100, 900, 500, 1000), "Start game", confidence = 0.9))
            }
            val screen = FakeScreens.opaqueCanvas()
            var tapped = false
            screen.clickHandler = { _, _ -> }
            val driver = FakeUiDriver(screen)
            val session = DefaultAndroPilotSession(
                driver,
                SessionConfig(
                    settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                    postActionDelayMs = 0, retryBackoffMs = 1,
                    policy = com.andropilot.core.safety.DefaultSafetyPolicy.permissive(),
                    visionProvider = provider,
                    treatNoEffectAsFailure = false,
                ),
            )
            val result = session.click(Selector.text("Start game"))
            assertInstanceOf<ActionResult.Success>(result)
            assertTrue(driver.calls.any { it.startsWith("tap(") })
            tapped = true
            assertTrue(tapped)
        }

        @Test
        fun `a failing vision provider degrades to semantic perception with a warning`() = runTest {
            val provider = VisionProvider { _, _ -> error("the OCR backend is unavailable") }
            val driver = FakeUiDriver(FakeScreens.home())
            val session = DefaultAndroPilotSession(
                driver,
                SessionConfig(
                    settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
                    postActionDelayMs = 0,
                    policy = com.andropilot.core.safety.DefaultSafetyPolicy.permissive(),
                    visionProvider = provider,
                ),
            )
            val result = assertInstanceOf<ActionResult.Success>(session.observe(includeVisual = true))
            assertTrue(result.snapshot!!.warnings.any { it.contains("Visual perception failed") })
        }

        @Test
        fun `an unlabelled wide detection is guessed to be a button`() {
            val fused = PerceptionFusion.fuse(
                canvas,
                listOf(VisualElement(Bounds(100, 900, 900, 1000), "Continue", confidence = 0.9)),
                VisionConfig.DEFAULT,
            )
            assertEquals(
                ElementRole.BUTTON,
                fused.elements.single { it.source == PerceptionSource.VISUAL }.role,
            )
        }
    }
}
