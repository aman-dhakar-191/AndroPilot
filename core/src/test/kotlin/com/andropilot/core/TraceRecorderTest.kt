package com.andropilot.core

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.action.InteractionMode
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.observe.TraceRecorder
import com.andropilot.core.observe.TraceWriter
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.DefaultAndroPilotSession
import com.andropilot.core.session.SessionConfig
import com.andropilot.core.testing.FakeScreens
import com.andropilot.core.testing.FakeUiDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Collects lines in memory so the suite never touches a filesystem. */
private class MemoryWriter : TraceWriter {
    val lines = mutableListOf<String>()
    var closed = false
    override fun append(line: String) { lines += line }
    override fun close() { closed = true }
}

class TraceRecorderTest {

    private fun recorderOver(
        writer: MemoryWriter,
        options: RecordingOptions = RecordingOptions(),
    ) = TraceRecorder(writer, options) { 1_000L }

    @Test
    fun `every line is valid standalone JSON`() = runTest {
        val writer = MemoryWriter()
        drive(writer)
        assertTrue(writer.lines.isNotEmpty())
        writer.lines.forEach { line ->
            Json.parseToJsonElement(line) // throws if the line is not self-contained
            assertFalse(line.contains('\n')) { "a record spanned lines: $line" }
        }
    }

    @Test
    fun `an action record carries the outcome, timing and interaction mode`() {
        val writer = MemoryWriter()
        recorderOver(writer).record(
            ActionResult.Success(
                action = AgentAction.Click(Selector.text("OK")),
                durationMs = 42,
                interactionMode = InteractionMode.GESTURE,
            ),
        )
        val o = Json.parseToJsonElement(writer.lines.single()).jsonObject
        assertEquals("action", o["kind"]!!.jsonPrimitive.content)
        assertEquals("click", o["action"]!!.jsonPrimitive.content)
        assertEquals("gesture", o["mode"]!!.jsonPrimitive.content)
        assertEquals(42, o["ms"]!!.jsonPrimitive.content.toInt())
        assertTrue(o["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a failure record carries the machine-readable reason and the hint`() {
        val writer = MemoryWriter()
        recorderOver(writer).record(
            ActionResult.Failure(
                action = AgentAction.Click(Selector.text("Nope")),
                durationMs = 3,
                reason = FailureReason.ELEMENT_NOT_FOUND,
                message = "nothing matched",
                recommendation = "scroll first",
            ),
        )
        val o = Json.parseToJsonElement(writer.lines.single()).jsonObject
        assertEquals("element_not_found", o["reason"]!!.jsonPrimitive.content)
        // The default redacts prose, so the hint is withheld; the reason still identifies it.
        assertFalse(o.containsKey("hint"))

        val verbose = MemoryWriter()
        TraceRecorder(verbose, RecordingOptions(includeText = true)) { 1L }.record(
            ActionResult.Failure(
                action = AgentAction.Click(Selector.text("Nope")),
                durationMs = 3,
                reason = FailureReason.ELEMENT_NOT_FOUND,
                message = "nothing matched",
                recommendation = "scroll first",
            ),
        )
        assertEquals(
            "scroll first",
            Json.parseToJsonElement(verbose.lines.single()).jsonObject["hint"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `screen text is redacted by default but structure survives`() = runTest {
        val writer = MemoryWriter()
        drive(writer, RecordingOptions(includeText = false))
        val joined = writer.lines.joinToString("\n")
        // The login screen's labels must not be in the file...
        assertFalse(joined.contains("Welcome back")) { joined.take(400) }
        assertFalse(joined.contains("sam@example.com")) { joined.take(400) }
        // ...nor may the prose the SDK composes from it.
        assertFalse(joined.contains("Sign in")) { joined.take(400) }
        // ...but everything needed to analyse perception must be.
        assertTrue(joined.contains("text_field"))
        assertTrue(joined.contains("resource_id"))
        assertTrue(joined.contains("bounds"))
        assertTrue(joined.contains("element_not_found"))
        assertTrue(joined.contains("\"redacted\":true"))
    }

    @Test
    fun `text is written only when a developer opts in`() = runTest {
        val writer = MemoryWriter()
        drive(writer, RecordingOptions(includeText = true))
        assertTrue(writer.lines.joinToString("\n").contains("Welcome back"))
    }

    @Test
    fun `recording stops at the size cap instead of filling the device`() {
        val writer = MemoryWriter()
        val recorder = recorderOver(writer, RecordingOptions(maxBytes = 400))
        repeat(50) { recorder.note("marker $it") }
        assertTrue(recorder.isFull)
        assertTrue(recorder.bytesWritten <= 400 + 200) { "wrote ${recorder.bytesWritten}" }
        assertTrue(writer.lines.last().contains("Recording stopped"))
        // Nothing further is appended once full.
        val count = writer.lines.size
        recorder.note("ignored")
        assertEquals(count, writer.lines.size)
    }

    @Test
    fun `snapshots can be omitted while the summary is kept`() = runTest {
        val writer = MemoryWriter()
        drive(writer, RecordingOptions(includeSnapshots = false))
        val joined = writer.lines.joinToString("\n")
        assertFalse(joined.contains("\"snapshot\""))
        assertTrue(joined.contains("\"elements\""))
    }

    @Test
    fun `a failing writer never breaks the session`() = runTest {
        val exploding = object : TraceWriter {
            override fun append(line: String) = error("disk is on fire")
            override fun close() = Unit
        }
        val session = session(TraceRecorder(exploding))
        val result = session.observe()
        assertTrue(result.isSuccess) { "a broken recorder took the action down with it" }
    }

    @Test
    fun `toFile appends rather than truncating, so a rerun keeps the earlier run`() {
        val dir = Files.createTempDirectory("andropilot-trace").toFile()
        val file = File(dir, "nested/trace.jsonl")
        TraceRecorder.toFile(file).use { it.note("first run") }
        TraceRecorder.toFile(file).use { it.note("second run") }
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("first run"))
        assertTrue(lines[1].contains("second run"))
        dir.deleteRecursively()
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun session(recorder: TraceRecorder) = DefaultAndroPilotSession(
        FakeUiDriver(FakeScreens.login()),
        SessionConfig(
            settleTimeoutMs = 20, settleQuietPeriodMs = 0, pollIntervalMs = 1,
            postActionDelayMs = 0, retryBackoffMs = 1,
            policy = DefaultSafetyPolicy.permissive(),
            recorder = recorder,
        ),
    )

    private suspend fun drive(writer: MemoryWriter, options: RecordingOptions = RecordingOptions()) {
        val s = session(TraceRecorder(writer, options) { 1_000L })
        s.observe()
        s.typeText("sam@example.com", Selector.id("email"))
        s.click("Nonexistent")
    }
}
