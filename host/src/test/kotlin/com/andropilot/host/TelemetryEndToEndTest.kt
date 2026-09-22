package com.andropilot.host

import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.telemetry.HttpTelemetryTransport
import com.andropilot.telemetry.TelemetryOptions
import com.andropilot.telemetry.TelemetrySink
import com.andropilot.telemetry.TelemetryState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The whole telemetry pipe, over a real socket.
 *
 * Every piece of this has its own test and every piece passed while telemetry did not
 * work, because what was broken was never inside a piece: it was the endpoint, the token,
 * or the assumption that a constructed sink meant a reachable server. This test is the
 * only one that can fail for those reasons.
 */
class TelemetryEndToEndTest {

    @Test
    fun `a sink reaches a real ingest server and the record lands on disk`(@TempDir dir: File) {
        val data = File(dir, "telemetry-data")
        val spool = File(dir, "spool")
        val logs = mutableListOf<String>()

        IngestServer(0, data, token = "shared", log = { logs += it }).start().use { ingest ->
            val connected = CountDownLatch(1)
            val sink = TelemetrySink.create(
                HttpTelemetryTransport("http://127.0.0.1:${ingest.port}/ingest", "shared"),
                spool,
                TelemetryOptions(
                    deviceId = "device-1",
                    sdkVersion = "0.1.0",
                    runId = "run-1",
                    recording = RecordingOptions(includeSnapshots = false),
                    flushIntervalMs = 200,
                    log = { logs += it },
                ),
            )
            sink.onStatus = { if (it.state == TelemetryState.CONNECTED) connected.countDown() }

            // The handshake alone should prove the pipe, before anything is recorded.
            assertTrue(connected.await(10, TimeUnit.SECONDS), "the handshake never reached the server: $logs")

            sink.onEvent(AgentEvent.Note("a thing happened", emptyMap(), at = System.currentTimeMillis()))
            sink.use { it.flush() }

            val file = data.listFiles()?.single { it.name.startsWith("events-") }
            assertNotNull(file, "nothing was written to $data")
            val written = file!!.readLines().filter(String::isNotBlank)

            // The handshake is a record like any other, so it is on disk with the rest --
            // which is what makes "did this device ever connect" answerable afterwards.
            assertTrue(
                written.any { it.contains("telemetry_initialized") },
                "the handshake should be persisted with everything else: $written",
            )
            assertTrue(written.all { it.contains(""""device":"device-1"""") }, "$written")
            assertTrue(written.all { it.contains(""""run":"run-1"""") }, "$written")

            assertEquals(written.size.toLong(), ingest.records)
            assertTrue(sink.status.uploaded >= 1, sink.status.describe())
            assertEquals(0, sink.status.consecutiveFailures, sink.status.describe())
            assertEquals(200, sink.status.lastStatus)
        }
    }

    @Test
    fun `a wrong token is reported as refused rather than as silence`(@TempDir dir: File) {
        // The failure this is really about: telemetry that says "initialized" and then
        // never sends anything looks exactly like a device with nothing to report.
        val data = File(dir, "telemetry-data")
        val logs = mutableListOf<String>()

        IngestServer(0, data, token = "right", log = { logs += it }).start().use { ingest ->
            val failed = CountDownLatch(1)
            val sink = TelemetrySink.create(
                HttpTelemetryTransport("http://127.0.0.1:${ingest.port}/ingest", "wrong"),
                File(dir, "spool"),
                TelemetryOptions(deviceId = "d", runId = "r", flushIntervalMs = 200, log = { logs += it }),
            )
            sink.onStatus = { if (it.state == TelemetryState.FAILED) failed.countDown() }

            assertTrue(failed.await(10, TimeUnit.SECONDS), "a refused token should be reported: $logs")
            sink.use {
                assertEquals(401, it.status.lastStatus)
                assertTrue(it.status.lastError?.contains("token") == true, it.status.describe())
            }
            assertTrue(logs.any { it.contains("token does not match") }, "the host should say why: $logs")
            assertEquals(0, ingest.records)
        }
    }
}
