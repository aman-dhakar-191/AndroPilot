package com.andropilot.android

import android.util.Log
import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.observe.RecordingOptions
import com.andropilot.core.util.AndroPilotJson
import kotlinx.serialization.encodeToString

/**
 * Streams events to Logcat as they happen.
 *
 * The zero-setup way to watch a session live:
 *
 * ```
 * adb logcat -s AndroPilot-events
 * ```
 *
 * No file to pull, no port to forward, nothing to remember to turn off before shipping
 * beyond not registering it. Two formats, because they answer different questions:
 * [Format.SUMMARY] is one readable line per event for watching a run unfold, and
 * [Format.JSON] emits the same wire format the SDK uses everywhere else, so a piped
 * `adb logcat` can be fed straight into `jq`.
 *
 * Logcat truncates very long lines, so [RecordingOptions.includeSnapshots] is off by
 * default here even in JSON mode -- a full hierarchy does not survive the trip. Use a
 * [com.andropilot.core.observe.TraceRecorder] when you need the snapshots.
 */
public class LogcatEventListener(
    private val tag: String = DEFAULT_TAG,
    private val format: Format = Format.SUMMARY,
    private val options: RecordingOptions = RecordingOptions(includeSnapshots = false),
) : AgentEventListener {

    public enum class Format { SUMMARY, JSON }

    override fun onEvent(event: AgentEvent) {
        val line = when (format) {
            Format.SUMMARY -> event.summarize()
            Format.JSON -> runCatching {
                AndroPilotJson.instance.encodeToString(prepare(event))
            }.getOrElse { event.summarize() }
        }
        when (event) {
            is AgentEvent.ActionFinished ->
                if (event.result.isSuccess) Log.i(tag, line) else Log.w(tag, line)
            is AgentEvent.ConfirmationRequired -> Log.w(tag, line)
            else -> Log.d(tag, line)
        }
    }

    /** Applies the same redaction rule the file recorder uses. */
    private fun prepare(event: AgentEvent): AgentEvent = when {
        event !is AgentEvent.SnapshotCaptured -> event
        !options.includeSnapshots -> AgentEvent.Note(
            message = "snapshot: ${event.snapshot.elements.size} elements in " +
                (event.snapshot.packageName ?: "unknown"),
            at = event.at,
        )
        options.includeText -> event
        else -> event.copy(
            snapshot = event.snapshot.copy(
                windowTitle = null,
                elements = event.snapshot.elements.map {
                    it.copy(text = null, contentDescription = null, hint = null)
                },
            ),
        )
    }

    public companion object {
        /** Distinct from the SDK's own log tag so a live stream can be isolated. */
        public const val DEFAULT_TAG: String = "AndroPilot-events"
    }
}
