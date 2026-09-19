package com.andropilot.telemetry

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.FailureReason
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.RecordingOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TelemetrySinkTest {

    /** Records what it was handed and can be told to fail, so no test opens a socket. */
    private class FakeTransport(var outcome: UploadOutcome = UploadOutcome.ACCEPTED) : TelemetryTransport {
        val batches = mutableListOf<TelemetryBatch>()
        override fun upload(batch: TelemetryBatch): UploadOutcome {
            batches += batch
            return outcome
        }
    }

    private fun sink(
        dir: File,
        transport: TelemetryTransport,
        recording: RecordingOptions = RecordingOptions(includeSnapshots = false),
    ) = TelemetrySink.create(
        transport,
        dir,
        TelemetryOptions(deviceId = "device-1", sdkVersion = "0.1.0", runId = "run-1", recording = recording),
        startUploader = false,
    )

    private fun failure(message: String) = AgentEvent.ActionFinished(
        sequence = 1,
        result = ActionResult.Failure(
            action = AgentAction.Observe(),
            durationMs = 12,
            reason = FailureReason.STALE_ELEMENT,
            message = message,
        ),
        at = 1_700_000_000_000,
    )

    @Test
    fun `uploads what a session produced`(@TempDir dir: File) {
        val transport = FakeTransport()
        sink(dir, transport).use { sink ->
            sink.onEvent(AgentEvent.Note("started", mapOf("task" to "open wifi"), at = 1))
            sink.onEvent(failure("the node went away"))
            assertEquals(1, sink.flush())
        }
        assertEquals(1, transport.batches.size)
        val batch = transport.batches.single()
        assertEquals("run-1", batch.runId)
        assertEquals("device-1", batch.deviceId)
        assertEquals(2, batch.lines.size)
        assertTrue(batch.lines[0].contains("\"kind\":\"note\""))
        assertTrue(batch.lines[1].contains("\"reason\":\"stale_element\""))
    }

    @Test
    fun `withholds screen-derived prose by default`(@TempDir dir: File) {
        val transport = FakeTransport()
        sink(dir, transport).use { sink ->
            sink.onEvent(failure("no element matched 'Transfer to Rahul'"))
            sink.flush()
        }
        val line = transport.batches.single().lines.single()
        // The failure message quotes what was on the screen. Shipping it to a server is
        // exactly the leak the redaction default exists to prevent.
        assertFalse(line.contains("Rahul"), "screen text reached the server: $line")
        assertTrue(line.contains("\"redacted\":true"))
        // What is left still has to be worth analysing.
        assertTrue(line.contains("\"reason\":\"stale_element\""))
        assertTrue(line.contains("\"ms\":12"))
    }

    @Test
    fun `sends screen text only when the host opts in`(@TempDir dir: File) {
        val transport = FakeTransport()
        sink(dir, transport, RecordingOptions(includeSnapshots = false, includeText = true)).use { sink ->
            sink.onEvent(failure("no element matched 'Transfer to Rahul'"))
            sink.flush()
        }
        assertTrue(transport.batches.single().lines.single().contains("Rahul"))
    }

    @Test
    fun `keeps records when the server is unreachable and sends them after it returns`(@TempDir dir: File) {
        val transport = FakeTransport(UploadOutcome.RETRY)
        sink(dir, transport).use { sink ->
            sink.onEvent(AgentEvent.Note("offline", at = 1))
            assertEquals(0, sink.flush())
            assertTrue(dir.listFiles()!!.any { it.length() > 0 }, "the batch was not kept on disk")

            transport.outcome = UploadOutcome.ACCEPTED
            assertEquals(1, sink.flush())
        }
        assertEquals(2, transport.batches.size, "the same batch should have been retried, not dropped")
        assertEquals(transport.batches[0].lines, transport.batches[1].lines)
    }

    @Test
    fun `discards a batch the server refuses rather than blocking everything behind it`(@TempDir dir: File) {
        val transport = FakeTransport(UploadOutcome.REJECTED)
        sink(dir, transport).use { sink ->
            sink.onEvent(AgentEvent.Note("bad", at = 1))
            sink.flush()
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".jsonl") && it.length() > 0 })
        }
    }

    @Test
    fun `drops the oldest records rather than filling the disk`(@TempDir dir: File) {
        val transport = FakeTransport(UploadOutcome.RETRY)
        val sink = TelemetrySink.create(
            transport,
            dir,
            TelemetryOptions(
                deviceId = "d",
                runId = "r",
                maxSegmentBytes = 200,
                maxSpoolBytes = 1_000,
            ),
            startUploader = false,
        )
        sink.use {
            repeat(200) { i -> sink.onEvent(AgentEvent.Note("event number $i with some padding", at = i.toLong())) }
            assertTrue(sink.dropped > 0, "nothing was dropped, so the cap did not hold")
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 1_200, "the spool grew past its cap")
        }
    }

    @Test
    fun `refuses to post in the clear to a public host`() {
        val failure = assertThrows<IllegalArgumentException> {
            HttpTelemetryTransport("http://telemetry.example.com/ingest", "t")
        }
        assertTrue(failure.message!!.contains("plaintext"))
        // A private address is the self-hosted case the project is actually built for.
        HttpTelemetryTransport("http://192.168.1.10:8080/ingest", "t")
        HttpTelemetryTransport("https://telemetry.example.com/ingest", "t")
    }
}
