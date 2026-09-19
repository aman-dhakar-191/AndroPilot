package com.andropilot.core.observe

import com.andropilot.core.action.ActionResult
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.util.AndroPilotJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

/** What a [TraceRecorder] writes, and how much of it. */
public data class RecordingOptions(
    /**
     * Embed the post-action snapshot. This is what makes a trace useful for diagnosing
     * perception problems -- hierarchy depth, wrapper noise, missing labels -- rather than
     * just telling you an action failed.
     */
    @get:JvmName("includeSnapshots")
    val includeSnapshots: Boolean = true,
    /**
     * Include on-screen and typed text.
     *
     * **Off by default, and that default is load-bearing.** A snapshot holds whatever was on
     * the screen: messages, account names, notification contents, a one-time code. With this
     * off the recorder still writes roles, bounds, resource ids, flags and tree structure --
     * everything needed to analyse how well the SDK perceives a screen, and nothing that
     * identifies whose screen it was. Turn it on for a debugging session on a device you
     * control, not for anything you will share.
     */
    val includeText: Boolean = false,
    /** Stop writing past this size rather than filling the device. */
    val maxBytes: Long = 8L * 1024 * 1024,
    /** Cap on elements per recorded snapshot. */
    val maxSnapshotElements: Int = 250,
)

/** Where a [TraceRecorder] puts its lines. Abstracted so tests do not touch a filesystem. */
public interface TraceWriter : AutoCloseable {
    public fun append(line: String)
}

/**
 * Appends a session's activity to a JSON Lines file for inspection during development.
 *
 * **This is a local development tool, not telemetry.** Nothing leaves the device; the SDK
 * has no network code and no reporting endpoint. It exists because the fastest way to
 * improve automation is to look at what the SDK actually perceived on a real screen, and a
 * screenshot of a log is a poor substitute for the data.
 *
 * JSON Lines rather than one JSON document: the file stays valid after a crash, appends
 * need no rewriting, and `grep`, `jq` and `wc -l` all work on it directly.
 *
 * Failures are swallowed. A recorder that breaks automation would be worse than no recorder.
 */
public class TraceRecorder(
    private val writer: TraceWriter,
    private val options: RecordingOptions = RecordingOptions(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentEventListener, AutoCloseable {

    /**
     * Records the events worth keeping.
     *
     * `SnapshotCaptured` is skipped: the snapshot that matters is the one after an action,
     * and that already travels inside the result. Recording every capture would multiply the
     * file size for the observations the settle loop makes along the way.
     */
    override fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ActionFinished -> record(event.result)
            is AgentEvent.Note -> note(event.message, event.data)
            is AgentEvent.ConfirmationRequired -> writeConfirmation(event)
            is AgentEvent.ConfirmationResolved -> write(
                buildJsonObject {
                    put("kind", "confirmation_resolved")
                    put("t", event.at)
                    put("id", event.id)
                    put("outcome", event.outcome.name.lowercase())
                }.toString(),
            )
            is AgentEvent.ActionStarted, is AgentEvent.SnapshotCaptured -> Unit
        }
    }

    private fun writeConfirmation(event: AgentEvent.ConfirmationRequired) {
        write(
            buildJsonObject {
                put("kind", "confirmation_required")
                put("t", event.at)
                put("id", event.confirmation.id)
                put("risk", event.confirmation.risk.name.lowercase())
                put("action", event.confirmation.action.name)
                // The description quotes the target's label, so it is screen content.
                if (options.includeText) {
                    put("description", event.confirmation.description)
                    put("reasons", encode(event.confirmation.reasons))
                }
            }.toString(),
        )
    }


    private val lock = Any()
    private var sequence: Long = 0
    private var written: Long = 0
    private var full = false
    private var closed = false

    /** Bytes appended so far. */
    public val bytesWritten: Long get() = synchronized(lock) { written }

    /** True once [RecordingOptions.maxBytes] is reached and recording has stopped. */
    public val isFull: Boolean get() = synchronized(lock) { full }

    /**
     * Appends one action record.
     *
     * With [RecordingOptions.includeText] off, only fields that cannot contain screen
     * content are written. That rules out more than the snapshot: `matchReason` quotes the
     * text it matched, a diff summary names the labels that appeared and disappeared, a
     * failure message lists candidate elements, and a recommendation prints the visible
     * labels. Each is prose the SDK composed *from* the screen. The machine-readable fields
     * -- outcome, reason, timing, interaction mode, element counts -- carry the diagnostic
     * value and none of the content, so those are always written.
     */
    public fun record(result: ActionResult) {
        val redact = !options.includeText
        val line = buildJsonObject {
            put("kind", "action")
            put("t", clock())
            put("seq", synchronized(lock) { ++sequence })
            put("action", result.action.name)
            put("ok", result.isSuccess)
            put("ms", result.durationMs)
            put("redacted", redact)
            when (result) {
                is ActionResult.Success -> {
                    put("mode", result.interactionMode.name.lowercase())
                    result.target?.let {
                        put("target", if (redact) structural(it) else it.describe())
                    }
                    if (!redact) result.matchReason?.let { put("match", it) }
                    result.diff?.let { diff ->
                        put("changed", diff.changed)
                        if (!redact) put("change", diff.summarize())
                    }
                    if (result.warnings.isNotEmpty()) {
                        put("warnings", encode(result.warnings))
                    }
                    result.snapshot?.let { putSnapshot(this, it) }
                }
                is ActionResult.Failure -> {
                    put("reason", result.reason.name.lowercase())
                    put("candidates", result.candidates.size)
                    if (redact) {
                        result.candidates.firstOrNull()
                            ?.let { put("best_candidate", structural(it.element)) }
                    } else {
                        put("message", result.message)
                        result.recommendation?.let { put("hint", it) }
                        if (result.candidates.isNotEmpty()) {
                            put(
                                "candidate_elements",
                                encode(result.candidates.map { it.element.describe() }),
                            )
                        }
                    }
                    result.snapshot?.let { putSnapshot(this, it) }
                }
            }
        }
        write(line.toString())
    }

    /** An element described by structure alone: role, view id and geometry, never its label. */
    private fun structural(element: com.andropilot.core.model.UiElement): String = buildString {
        append(element.role.name.lowercase())
        element.resourceId?.substringAfterLast('/')?.let { append(" #").append(it) }
        append(' ').append(element.bounds)
        if (!element.enabled) append(" disabled")
    }

    /** Writes a free-form marker, so a developer can label a run before reproducing a bug. */
    public fun note(message: String, data: Map<String, String> = emptyMap()) {
        val line = buildJsonObject {
            put("kind", "note")
            put("t", clock())
            put("message", message)
            data.forEach { (k, v) -> put(k, v) }
        }
        write(line.toString())
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { writer.close() }
    }

    private fun putSnapshot(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        snapshot: UiSnapshot,
    ) {
        builder.put("elements", snapshot.elements.size)
        builder.put("package", snapshot.packageName ?: "unknown")
        if (!options.includeSnapshots) return
        val prepared = prepare(snapshot)
        builder.put("snapshot", AndroPilotJson.instance.encodeToJsonElement(prepared))
    }

    /**
     * Trims a snapshot to what the options allow.
     *
     * Redaction removes text, descriptions, hints and the window title, and keeps roles,
     * bounds, resource ids, flags and parent/child links. Resource ids are developer-assigned
     * view identifiers rather than user content, and dropping them would make a trace close
     * to useless for the perception analysis this exists to support.
     */
    private fun prepare(snapshot: UiSnapshot): UiSnapshot {
        val capped = if (snapshot.elements.size <= options.maxSnapshotElements) {
            snapshot
        } else {
            snapshot.copy(elements = snapshot.elements.take(options.maxSnapshotElements))
        }
        if (options.includeText) return capped
        return capped.copy(
            windowTitle = null,
            elements = capped.elements.map {
                it.copy(text = null, contentDescription = null, hint = null)
            },
        )
    }

    private fun encode(values: List<String>): JsonElement =
        AndroPilotJson.instance.encodeToJsonElement(values)

    private fun write(line: String) {
        val payload = synchronized(lock) {
            if (closed || full) return
            val bytes = line.length.toLong() + 1
            if (written + bytes > options.maxBytes) {
                full = true
                val notice = buildJsonObject {
                    put("kind", "note")
                    put("t", clock())
                    put("message", "Recording stopped: reached maxBytes=${options.maxBytes}.")
                }.toString()
                written += notice.length + 1
                notice
            } else {
                written += bytes
                line
            }
        }
        // Outside the lock: a slow or failing sink must not serialize the whole session.
        runCatching { writer.append(payload) }
    }

    public companion object {
        /**
         * Records to [file], creating parent directories and appending to anything already
         * there. On Android, `context.getExternalFilesDir(null)` is the practical choice: it
         * needs no permission and is reachable with `adb pull`.
         */
        public fun toFile(
            file: File,
            options: RecordingOptions = RecordingOptions(),
        ): TraceRecorder = TraceRecorder(FileTraceWriter(file), options)
    }
}

/** Appends lines to a file, flushing each one so a crash cannot lose the last record. */
public class FileTraceWriter(private val file: File) : TraceWriter {

    private val out by lazy {
        file.parentFile?.mkdirs()
        java.io.PrintWriter(java.io.FileWriter(file, true), true)
    }

    override fun append(line: String) {
        out.println(line)
    }

    override fun close() {
        runCatching { out.close() }
    }
}
