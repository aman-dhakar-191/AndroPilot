package com.andropilot.host.agent

import com.andropilot.protocol.ToolSpec

/** One turn in the conversation the loop keeps with the model. */
public sealed interface Turn {
    /** Standing instructions. Sent once, first. */
    public data class System(val text: String) : Turn

    /** What the human asked for. */
    public data class User(val text: String) : Turn

    /** What the model said, and what it wants run. */
    public data class Assistant(val text: String?, val toolCalls: List<ToolCall>) : Turn

    /** What running one tool produced. */
    public data class ToolResult(val callId: String, val name: String, val content: String) : Turn
}

/** A tool the model asked for, with its arguments as raw JSON. */
public data class ToolCall(
    val id: String,
    val name: String,
    /** A JSON object. Kept as text: it is forwarded to the device, not interpreted here. */
    val argumentsJson: String,
)

/** What the model replied. */
public data class ModelReply(
    val text: String?,
    val toolCalls: List<ToolCall>,
) {
    /** No tool calls means the model believes it is finished. */
    public val isFinal: Boolean get() = toolCalls.isEmpty()
}

/**
 * Somewhere to send a conversation and get the next turn back.
 *
 * An interface rather than a concrete client because provider-neutrality is the point of
 * this project: the SDK deliberately describes its capabilities in a form any runtime can
 * consume, and it would be odd for the host in front of it to then hard-wire one vendor's
 * HTTP shape. Adding a differently-shaped backend means another implementation of this and
 * nothing else changes.
 */
public interface ModelClient {
    public fun complete(turns: List<Turn>, tools: List<ToolSpec>): ModelReply

    /** For logs and for the telemetry record of which model made the decisions. */
    public val describe: String
}
