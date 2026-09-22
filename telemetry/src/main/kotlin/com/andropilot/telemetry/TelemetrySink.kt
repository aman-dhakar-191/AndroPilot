package com.andropilot.telemetry

import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.observe.TraceRecorder
import com.andropilot.core.observe.TraceWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** How a [TelemetrySink] behaves. */
public data class TelemetryOptions(
    /** Identifies this device across runs. Random and persisted by the host app -- never a hardware id. */
    val deviceId: String,
    val sdkVersion: String = "unknown",
    /** One run of the agent. Lets the server group a task's events without correlating timestamps. */
    val runId: String = UUID.randomUUID().toString(),
    /**
     * What is recorded, and how much of it.
     *
     * Reused from the file recorder rather than redefined, which is the point: there is one
     * description in the codebase of what may leave a device, and `includeText` defaults to
     * false here for the same reason it does there. Turning it on ships screen contents to
     * a server.
     */
    val recording: RecordingOptions = RecordingOptions(includeSnapshots = false),
    /** Seal and upload at least this often, even if the batch is small. */
    val flushIntervalMs: Long = 15_000,
    /** Start a new segment past this size, so no single upload is enormous. */
    val maxSegmentBytes: Long = 256 * 1024,
    /** Hard ceiling on spooled bytes. Past it the oldest records are dropped, and counted. */
    val maxSpoolBytes: Long = 16L * 1024 * 1024,
    /** First retry delay after a failed upload. Doubles, capped at [maxBackoffMs]. */
    val initialBackoffMs: Long = 5_000,
    val maxBackoffMs: Long = 5 * 60_000,
    /**
     * Prove the endpoint is reachable at startup instead of waiting for the first flush.
     *
     * Without it the first evidence that telemetry works arrives a flush interval later,
     * and only if something happened to record -- so a misconfigured endpoint looks
     * identical to an idle device for fifteen seconds, which is exactly when somebody is
     * watching the screen wondering whether they typed the host right.
     */
    val handshake: Boolean = true,
    /**
     * Where this module's own diagnostics go.
     *
     * A hook rather than a logger because this code runs on Android and on a desktop JVM,
     * and the module deliberately depends on neither platform's logging. The host points
     * it at Logcat or stderr.
     */
    val log: (String) -> Unit = {},
)

/**
 * Ships a session's records to a server.
 *
 * **This is the one place in the project that sends anything off a device, and it is not
 * part of the SDK.** The SDK produces `AgentEvent`s and knows nothing about where they go;
 * this module is a listener a host opts into by adding a dependency and naming an endpoint.
 * Nothing is enabled by default and there is no endpoint baked in anywhere.
 *
 * Wiring is one line:
 * ```
 * SessionConfig(listeners = listOf(TelemetrySink.http(endpoint, token, deviceId)))
 * ```
 *
 * [onEvent] does no I/O. Listeners registered on a session are called synchronously on the
 * action path, so anything slow there slows automation down; this one formats the record
 * and appends it to a spool file, and a background thread does the uploading.
 *
 * The formatting is [TraceRecorder]'s, not a second implementation. That keeps one
 * definition of what a record looks like and one definition of what redaction removes, and
 * means a spooled telemetry batch is byte-identical to a local trace file -- so every
 * analysis script written against device traces works on the server's data unchanged.
 */
public class TelemetrySink private constructor(
    private val spool: Spool,
    private val transport: TelemetryTransport,
    private val options: TelemetryOptions,
) : AgentEventListener, AutoCloseable {

    private val recorder = TraceRecorder(SpoolWriter(spool), options.recording)
    private val running = AtomicBoolean(true)
    private var backoffMs = options.initialBackoffMs

    private val recorded = AtomicLong(0)
    private val uploadedRecords = AtomicLong(0)

    @Volatile
    private var current = TelemetryStatus(TelemetryState.INITIALIZING, describeEndpoint())

    /** Called whenever the status changes, on the uploader thread. */
    @Volatile
    public var onStatus: (TelemetryStatus) -> Unit = {}

    /** Everything known about the pipeline right now. Safe to read from any thread. */
    public val status: TelemetryStatus
        get() = current.copy(
            recorded = recorded.get(),
            uploaded = uploadedRecords.get(),
            dropped = spool.dropped,
            pendingBytes = pendingBytes,
        )

    /** Batches accepted by the server so far. */
    public var uploaded: Long = 0
        private set

    /** Number of bytes currently waiting in the local spool. */
    public val pendingBytes: Long get() = spool.pending().sumOf { it.length() }

    /** Records dropped because the spool filled up. A gap the server should be told about. */
    public val dropped: Long get() = spool.dropped

    private val worker = Thread({ loop() }, "andropilot-telemetry").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    override fun onEvent(event: AgentEvent) {
        recorded.incrementAndGet()
        recorder.onEvent(event)
    }

    /**
     * Posts one synthetic record so the pipeline is proven end to end at startup.
     *
     * The record goes through the ordinary path and lands in the same file as everything
     * else, which is the point: if this arrives, the endpoint, the token, the gzip, the
     * server and the file are all working, and any later silence is genuinely an absence
     * of events rather than a broken pipe.
     *
     * Runs on the uploader thread. On Android, doing it where the sink is constructed would
     * be network I/O on the main thread, which the platform refuses outright.
     */
    private fun handshake() {
        val endpoint = describeEndpoint()
        options.log("[telemetry] handshake: posting to $endpoint")
        val line = """{"type":"telemetry_initialized","run_id":"${options.runId}",""" +
            """"device_id":"${options.deviceId}","sdk_version":"${options.sdkVersion}",""" +
            """"at":${System.currentTimeMillis()}}"""
        val result = runCatching {
            transport.upload(TelemetryBatch(options.runId, options.deviceId, options.sdkVersion, listOf(line)))
        }.getOrElse { UploadResult(UploadOutcome.RETRY, null, it.message ?: it::class.java.simpleName) }
        record(result, records = 1, what = "handshake")
    }

    /**
     * Folds one attempt's outcome into the status, and says so.
     *
     * Every transition is logged rather than only failures: "connected" arriving late, or
     * not at all, is as diagnostic as an error, and a log that only speaks up when things
     * break cannot distinguish working from not running.
     */
    private fun record(result: UploadResult, records: Int, what: String) {
        val now = System.currentTimeMillis()
        val before = current
        current = when (result.outcome) {
            UploadOutcome.ACCEPTED -> {
                uploadedRecords.addAndGet(records.toLong())
                before.copy(
                    state = TelemetryState.CONNECTED,
                    batches = before.batches + 1,
                    consecutiveFailures = 0,
                    lastSuccessAtMs = now,
                    lastStatus = result.status,
                    lastError = null,
                )
            }
            UploadOutcome.RETRY -> before.copy(
                state = TelemetryState.RETRYING,
                consecutiveFailures = before.consecutiveFailures + 1,
                lastFailureAtMs = now,
                lastStatus = result.status,
                lastError = result.detail,
            )
            UploadOutcome.REJECTED -> before.copy(
                state = TelemetryState.FAILED,
                consecutiveFailures = before.consecutiveFailures + 1,
                lastFailureAtMs = now,
                lastStatus = result.status,
                lastError = result.detail,
            )
        }
        val snapshot = status
        options.log(
            when (result.outcome) {
                UploadOutcome.ACCEPTED -> "[telemetry] $what accepted ($records record(s), HTTP ${result.status}) -- ${snapshot.describe()}"
                UploadOutcome.RETRY ->
                    "[telemetry] $what failed, will retry in ${backoffMs}ms " +
                        "(HTTP ${result.status ?: "none"}: ${result.detail ?: "no detail"}) -- ${snapshot.describe()}"
                UploadOutcome.REJECTED ->
                    "[telemetry] $what refused and the batch was dropped " +
                        "(HTTP ${result.status ?: "none"}: ${result.detail ?: "no detail"}) -- ${snapshot.describe()}"
            },
        )
        runCatching { onStatus(snapshot) }
    }

    private fun describeEndpoint(): String = transport.let {
        if (it is HttpTelemetryTransport) it.describe else it::class.java.simpleName
    }

    /**
     * Uploads everything spooled so far, synchronously.
     *
     * Call it when a task ends: a run whose last batch is still buffered when the process
     * dies is the run you most wanted to look at.
     */
    public fun flush(): Int {
        spool.seal()
        var sent = 0
        for (segment in spool.pending()) {
            val lines = runCatching { segment.readLines().filter(String::isNotBlank) }.getOrNull()
            if (lines == null) {
                segment.delete()
                continue
            }
            if (lines.isEmpty()) {
                segment.delete()
                continue
            }
            options.log("[telemetry] uploading ${lines.size} record(s) from ${segment.name}")
            val result = transport.upload(
                TelemetryBatch(options.runId, options.deviceId, options.sdkVersion, lines),
            )
            when (result.outcome) {
                UploadOutcome.ACCEPTED -> {
                    segment.delete()
                    uploaded++
                    sent++
                    backoffMs = options.initialBackoffMs
                    record(result, lines.size, "upload")
                }
                // A rejected batch will be rejected again forever and would block every
                // batch behind it. Drop it and keep the pipe moving.
                UploadOutcome.REJECTED -> {
                    segment.delete()
                    record(result, lines.size, "upload")
                }
                UploadOutcome.RETRY -> {
                    backoffMs = (backoffMs * 2).coerceAtMost(options.maxBackoffMs)
                    record(result, lines.size, "upload")
                    return sent
                }
            }
        }
        return sent
    }

    private fun loop() {
        current = current.copy(state = TelemetryState.INITIALIZED)
        options.log("[telemetry] ${status.describe()}")
        runCatching { onStatus(status) }
        if (options.handshake) runCatching { handshake() }
        while (running.get()) {
            val waited = runCatching { Thread.sleep(options.flushIntervalMs) }.isSuccess
            if (!waited || !running.get()) return
            runCatching { flush() }
            if (backoffMs > options.initialBackoffMs) {
                runCatching { Thread.sleep(backoffMs) }
            }
        }
    }

    /** Flushes what is left and stops. */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        options.log("[telemetry] closing -- ${status.describe()}")
        worker.interrupt()
        runCatching { flush() }
        runCatching { recorder.close() }
    }

    private class SpoolWriter(private val spool: Spool) : TraceWriter {
        override fun append(line: String) {
            spool.append(line)
        }

        override fun close() {
            spool.seal()
        }
    }

    public companion object {
        /**
         * A sink that posts to an HTTP endpoint.
         *
         * [spoolDirectory] must be private to the app -- on Android, `context.cacheDir`.
         * Records sit there until they are accepted, and they are the same records a trace
         * file holds.
         */
        public fun http(
            endpoint: String,
            token: String,
            spoolDirectory: File,
            options: TelemetryOptions,
        ): TelemetrySink = create(HttpTelemetryTransport(endpoint, token), spoolDirectory, options)

        /** A sink over any transport. This is the seam tests and custom collectors use. */
        public fun create(
            transport: TelemetryTransport,
            spoolDirectory: File,
            options: TelemetryOptions,
            startUploader: Boolean = true,
        ): TelemetrySink {
            val sink = TelemetrySink(
                Spool(spoolDirectory, options.maxSegmentBytes, options.maxSpoolBytes),
                transport,
                options,
            )
            if (startUploader) sink.worker.start()
            return sink
        }
    }
}
