package com.andropilot.core.observe

import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.InteractionMode

public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }

/**
 * Sink for SDK diagnostics.
 *
 * Deliberately a tiny interface rather than a logging framework dependency: hosts wire it
 * to Logcat, Timber, a file, or a remote agent's trace stream.
 */
public fun interface AgentLogger {
    public fun log(level: LogLevel, tag: String, message: String, error: Throwable?)

    public companion object {
        public val NONE: AgentLogger = AgentLogger { _, _, _, _ -> }

        public fun console(minLevel: LogLevel = LogLevel.INFO): AgentLogger =
            AgentLogger { level, tag, message, error ->
                if (level.ordinal >= minLevel.ordinal && level != LogLevel.NONE) {
                    println("[$level] $tag: $message")
                    error?.printStackTrace()
                }
            }
    }
}

/** Convenience overload for the common no-exception case. */
public fun AgentLogger.log(level: LogLevel, tag: String, message: String): Unit =
    log(level, tag, message, null)

/**
 * Removes values that must never reach a log, a trace or a remote agent.
 *
 * The SDK redacts by default and opts *in* to showing text, rather than the other way
 * round: an automation component that can read every screen is one careless log line away
 * from leaking a password or a 2FA code.
 */
public class Redactor(
    /** When false, typed text and field contents are replaced with a length placeholder. */
    public val allowTextContent: Boolean = false,
) {
    public fun text(value: String?, sensitive: Boolean = false): String {
        if (value == null) return "null"
        if (sensitive || !allowTextContent) return "<redacted:${value.length} chars>"
        return value
    }

    public fun describeAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeText ->
            "type_text(text=${text(action.text, action.sensitive)}, replace=${action.replace})"
        is AgentAction.OpenIntent ->
            "open_intent(action=${action.action}, uri=${if (allowTextContent) action.uri else "<redacted>"})"
        else -> action.toString()
    }
}

/**
 * One entry in a session's action history.
 *
 * Together these answer the observability questions an integrator actually asks: what was
 * attempted, what the SDK saw, how the target was found, whether semantic or visual
 * interaction was used, and how long it took.
 */
public data class TraceEntry(
    val sequence: Long,
    val startedAt: Long,
    val durationMs: Long,
    val actionName: String,
    val actionDescription: String,
    val packageName: String?,
    val succeeded: Boolean,
    val interactionMode: InteractionMode,
    val matchReason: String?,
    val failureReason: String?,
    val message: String?,
    val uiChanged: Boolean?,
    val elementCount: Int?,
) {
    public fun format(): String = buildString {
        append('#').append(sequence).append(' ')
        append(if (succeeded) "OK  " else "FAIL")
        append(' ').append(actionName)
        append(" (").append(durationMs).append("ms")
        if (interactionMode != InteractionMode.NONE) append(", ").append(interactionMode.name.lowercase())
        append(')')
        packageName?.let { append(" in ").append(it) }
        matchReason?.let { append(" via ").append(it) }
        uiChanged?.let { append(if (it) " [ui changed]" else " [no ui change]") }
        failureReason?.let { append(" ").append(it) }
        message?.let { append(": ").append(it) }
    }
}

/**
 * A bounded, in-memory ring of [TraceEntry] plus a live log bridge.
 *
 * Bounded because an agent can run for hours; the demo app and any inspector UI read from
 * here rather than scraping Logcat.
 */
public class ActionTrace(
    private val capacity: Int = 200,
    private val logger: AgentLogger = AgentLogger.NONE,
    private val redactor: Redactor = Redactor(),
) {
    private val entries = ArrayDeque<TraceEntry>(capacity)
    private var sequence = 0L
    private val lock = Any()

    public fun record(result: ActionResult, startedAt: Long): TraceEntry {
        val entry = synchronized(lock) {
            val next = ++sequence
            val e = when (result) {
                is ActionResult.Success -> TraceEntry(
                    sequence = next,
                    startedAt = startedAt,
                    durationMs = result.durationMs,
                    actionName = result.action.name,
                    actionDescription = redactor.describeAction(result.action),
                    packageName = result.snapshot?.packageName ?: result.target?.let { null },
                    succeeded = true,
                    interactionMode = result.interactionMode,
                    matchReason = result.matchReason,
                    failureReason = null,
                    message = result.warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
                    uiChanged = result.diff?.changed,
                    elementCount = result.snapshot?.elements?.size,
                )
                is ActionResult.Failure -> TraceEntry(
                    sequence = next,
                    startedAt = startedAt,
                    durationMs = result.durationMs,
                    actionName = result.action.name,
                    actionDescription = redactor.describeAction(result.action),
                    packageName = result.snapshot?.packageName,
                    succeeded = false,
                    interactionMode = InteractionMode.NONE,
                    matchReason = result.candidates.firstOrNull()?.reason,
                    failureReason = result.reason.name.lowercase(),
                    message = result.message,
                    uiChanged = null,
                    elementCount = result.snapshot?.elements?.size,
                )
            }
            while (entries.size >= capacity) entries.removeFirst()
            entries.addLast(e)
            e
        }
        logger.log(
            if (entry.succeeded) LogLevel.INFO else LogLevel.WARN,
            TAG,
            entry.format(),
        )
        return entry
    }

    public fun snapshot(): List<TraceEntry> = synchronized(lock) { entries.toList() }

    public fun clear(): Unit = synchronized(lock) { entries.clear() }

    public fun formatAll(): String = snapshot().joinToString("\n") { it.format() }

    public companion object {
        public const val TAG: String = "AndroPilot"
    }
}
