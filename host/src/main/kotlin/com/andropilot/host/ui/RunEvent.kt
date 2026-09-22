package com.andropilot.host.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Something worth showing while a run is in progress.
 *
 * Separate from the SDK's `AgentEvent`, and deliberately so: that stream describes what the
 * *device* did and is the record telemetry keeps. This one describes what the *loop* did,
 * including the model's reasoning, which never reaches the device as anything but a note.
 * Merging them would mean the host inventing device events it did not observe.
 */
@Serializable
public sealed interface RunEvent {

    @Serializable
    @SerialName("started")
    public data class Started(val goal: String, val model: String, val tools: Int) : RunEvent

    /** What the model says it is about to do. The reason a run is reviewable at all. */
    @Serializable
    @SerialName("intent")
    public data class Intent(val step: Int, val text: String) : RunEvent

    @Serializable
    @SerialName("action")
    public data class Action(val step: Int, val name: String, val payload: String) : RunEvent

    @Serializable
    @SerialName("result")
    public data class Result(val step: Int, val name: String, val summary: String, val ok: Boolean) : RunEvent

    @Serializable
    @SerialName("finished")
    public data class Finished(val message: String?, val steps: Int, val actions: Int) : RunEvent

    /** The run ended without the model saying it was done: the step ceiling, or a stop. */
    @Serializable
    @SerialName("halted")
    public data class Halted(val reason: String, val steps: Int, val actions: Int) : RunEvent

    @Serializable
    @SerialName("failed")
    public data class Failed(val message: String) : RunEvent

    /** Connection state, so a page opened before the phone arrives is not just blank. */
    @Serializable
    @SerialName("device")
    public data class Device(val connected: Boolean, val name: String?, val tools: Int) : RunEvent
}

/**
 * Fans run events out to whoever is watching.
 *
 * Listeners are held in a copy-on-write list and every dispatch is isolated: a browser that
 * went away mid-write must not take down the run it was watching. Nothing is buffered for
 * late subscribers -- a page that opens halfway through a run sees the rest of it, which is
 * the honest thing to show rather than a replay that looks live.
 */
public class RunEventBus {
    private val listeners = CopyOnWriteArrayList<(RunEvent) -> Unit>()

    public fun subscribe(listener: (RunEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    public fun emit(event: RunEvent) {
        listeners.forEach { runCatching { it(event) } }
    }
}
