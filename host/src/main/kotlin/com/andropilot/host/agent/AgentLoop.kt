package com.andropilot.host.agent

import com.andropilot.host.AgentBridge
import com.andropilot.host.Skills
import com.andropilot.host.packageOf
import com.andropilot.host.ui.RunEvent
import kotlinx.serialization.json.Json

/** How a run ended. */
public data class RunOutcome(
    val finished: Boolean,
    /** The model's closing message, when it produced one. */
    val message: String?,
    val steps: Int,
    val actions: Int,
)

/**
 * Drives a device from a model, one step at a time.
 *
 * This is the piece that was missing. Everything else in the project either perceives a
 * screen or carries bytes; nothing called a model. MCP does not fill that gap -- an MCP
 * server answers a client that already owns a model, so pointing it at a model endpoint is
 * backwards. A loop that calls out to an endpoint is the other half, and the two coexist:
 * MCP for when a client like an IDE drives the phone, this for when the host does.
 *
 * What it does not do is decide what is allowed. The phone holds the `SafetyPolicy`, the
 * model's tool calls go through it exactly as an MCP client's would, and nothing here can
 * widen that. The model is the untrusted party in this arrangement -- it is text from a
 * server, driving a real phone -- so the only privilege it gets is the one the device
 * already granted.
 */
public class AgentLoop(
    private val bridge: AgentBridge,
    private val model: ModelClient,
    private val skills: Skills,
    /**
     * A hard stop. A model that has misread a screen will keep trying, and a loop with no
     * ceiling does that against somebody's actual phone.
     */
    private val maxSteps: Int = 40,
    private val log: (String) -> Unit = { System.err.println(it) },
    /** Where a watching UI gets its live view. Silent by default, for the plain CLI. */
    private val emit: (RunEvent) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var stopped = false

    /** Apps whose notes this run has already delivered. */
    private val delivered = mutableSetOf<String>()

    /**
     * Asks the run to stop after the step in flight.
     *
     * Not an interrupt: an action already dispatched to the phone is going to happen
     * whatever this flag says, and pretending otherwise would make "stop" a lie. What it
     * guarantees is that nothing further is asked of the model or the device.
     */
    public fun stop() {
        stopped = true
    }

    public fun run(goal: String): RunOutcome {
        val tools = bridge.tools()
        if (tools.isEmpty()) {
            throw IllegalStateException(
                "The device has not reported any tools. Connect the AndroPilot agent app first.",
            )
        }

        val turns = mutableListOf<Turn>(
            Turn.System(systemPrompt()),
            Turn.User(goal),
        )
        bridge.note("Run started: $goal", mapOf("model" to model.describe))
        emit(RunEvent.Started(goal, model.describe, tools.size))

        var actions = 0
        for (step in 1..maxSteps) {
            if (stopped) {
                bridge.note("Run stopped by the operator.", mapOf("outcome" to "stopped"))
                emit(RunEvent.Halted("Stopped.", step - 1, actions))
                return RunOutcome(finished = false, message = null, steps = step - 1, actions = actions)
            }

            val reply = try {
                model.complete(turns, tools)
            } catch (e: Exception) {
                emit(RunEvent.Failed(e.message ?: e::class.java.simpleName))
                throw e
            }

            // A reply with no tool calls is the model saying it is done, and its text is a
            // conclusion rather than a plan. Recorded under a different kind so a later
            // reading can tell "here is what I am about to do" from "here is what I think
            // happened" without inferring it from position.
            if (reply.isFinal) {
                reply.text?.let {
                    log("[model] $it")
                    bridge.note(it, mapOf("step" to step.toString(), "kind" to "conclusion"))
                }
                bridge.note("Run finished after $actions action(s).", mapOf("outcome" to "finished"))
                emit(RunEvent.Finished(reply.text, step, actions))
                return RunOutcome(finished = true, message = reply.text, steps = step, actions = actions)
            }

            // Otherwise the model's own words are the only record of what it was *trying*
            // to do. An action alone cannot say whether a tap was the right one or a guess,
            // so this goes into the device's event stream next to the outcome it produced
            // -- which is what makes it possible to ask later whether a decision was
            // correct, rather than only what happened.
            reply.text?.let {
                log("[model] $it")
                bridge.note(it, mapOf("step" to step.toString(), "kind" to "intent"))
                emit(RunEvent.Intent(step, it))
            }

            turns += Turn.Assistant(reply.text, reply.toolCalls)

            for (call in reply.toolCalls) {
                val known = tools.any { it.name == call.name }
                val content = if (!known) {
                    // Answered rather than dropped: every tool_call has to come back with a
                    // result or the next request is rejected, and telling the model the name
                    // was wrong is more useful than failing the run.
                    "FAILED [invalid_request] There is no tool called '${call.name}'. " +
                        "Available: ${tools.joinToString { it.name }}."
                } else {
                    actions++
                    val payload = actionPayload(call.name, call.argumentsJson, json)
                    log("[action] ${call.name} $payload")
                    emit(RunEvent.Action(step, call.name, payload))
                    val result = try {
                        bridge.execute(payload)
                    } catch (e: Exception) {
                        null
                    }
                    (result?.forModel ?: "FAILED [not_connected] The device could not be reached.")
                        .also { emit(RunEvent.Result(step, call.name, it, !it.startsWith("FAILED"))) }
                        .let { summary -> summary + notesFor(result?.payload) }
                }
                if (!known) emit(RunEvent.Result(step, call.name, content, ok = false))
                turns += Turn.ToolResult(call.id, call.name, content)
            }
        }

        bridge.note("Run stopped at the $maxSteps step limit.", mapOf("outcome" to "step_limit"))
        emit(RunEvent.Halted("Reached the $maxSteps step limit.", maxSteps, actions))
        return RunOutcome(finished = false, message = null, steps = maxSteps, actions = actions)
    }

    /**
     * Standing instructions.
     *
     * Deliberately about *this* device rather than about being helpful: the things a model
     * gets wrong here are Android-specific and repeat every run. App notes are appended
     * when there are any, because that knowledge belongs in front of the model rather than
     * being rediscovered.
     */
    /**
     * The notes for whatever app a result came from, the first time it is seen.
     *
     * Attached to the result rather than the prompt because that is when they are relevant
     * and when the model is already reading. Once per run per app: repeating them every
     * turn would crowd out the screen itself, which is the thing that actually decides the
     * next action.
     */
    private fun notesFor(payload: String?): String {
        val pkg = payload?.let(::packageOf) ?: return ""
        val skill = skills.forPackage(pkg)
        val key = if (skill != null) pkg else UNKNOWN_KEY
        if (!delivered.add(key)) return ""
        val notes = (skill ?: skills.unknown())?.notes ?: return ""
        val heading = if (skill != null) "notes for $pkg" else "notes for an app without specific notes"
        return "\n\n--- $heading ---\n$notes"
    }

    private fun systemPrompt(): String = buildString {
        append(
            """
            You are driving a real Android phone through an accessibility service. Somebody
            is holding this device; act like it.

            How to work:
            - Call `observe` before you act, and again after anything that may have changed
              the screen. Element ids belong to one snapshot and are meaningless in the next.
            - Prefer a semantic selector (the visible text, or a resource id) over
              coordinates. Coordinates are a last resort and they break on other devices.
            - Every tool reports either success or a machine-readable failure reason. Read it.
              `element_not_found` after a scroll is different from `stale_element`, which is
              different from `blocked_by_policy`, and retrying blindly helps in none of them.
            - If something is below the fold, scroll before concluding it is absent.
            - `ambiguous_target` means two things matched equally well. Do not pick one at
              random -- look at the candidates and say which you meant.
            - Some actions need a human to approve them on the device. If one comes back
              needing confirmation, say so and stop; do not try to work around it.
            - Say what you are about to do and why, in one line, before each step. That line
              is recorded alongside the result and is what makes a run reviewable.

            When the task is done, or you are stuck, reply with plain text and no tool call.
            """.trimIndent(),
        )
        skills.global()?.let { global ->
            append("\n\nGeneral Android operating guidance:\n")
            append(global.notes).append('\n')
        }
        // An index, not the notes themselves. Holding every app's notes in context on every
        // turn costs the whole library's worth of tokens to help with the one app a run
        // actually opens, and grows worse with each skill written -- which would make a
        // useful library the thing that ruins the prompt. The notes for an app arrive with
        // the first result from it instead.
        val index = skills.index()
        if (index.isNotEmpty()) {
            append("\n\nNotes have been written for these apps, and will be given to you the ")
            append("first time each one appears on screen. Do not ask for them:\n")
            index.forEach { append("- ").append(it).append('\n') }
        }
    }

    private companion object {
        /** One key for every app without notes: the playbook is the same for all of them. */
        const val UNKNOWN_KEY = "_unknown"
    }
}
