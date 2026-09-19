package com.andropilot.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The protocol revision this build speaks.
 *
 * Sent in [Frame.Hello] and checked by the host. A phone and a PC are updated
 * independently -- the phone through an APK the user sideloads, the host through a `git
 * pull` -- so they will be out of step regularly, and a mismatch has to produce a clear
 * message rather than a confusing parse failure three frames later.
 */
public const val PROTOCOL_VERSION: Int = 1

/**
 * One message on the agent socket.
 *
 * The envelope is deliberately thin. Everything that describes an action or its outcome
 * travels as an opaque string in [Frame.ActionRequest.payload] and
 * [Frame.ActionResponse.payload], because that string is already defined: it is exactly
 * what `ToolCodec.executeJson` consumes and returns. Re-modelling actions here would create
 * a second schema that drifts from the first, and the SDK's action model is the one that
 * has to stay authoritative.
 *
 * Every request carries an [id] and its response echoes it, so several actions can be in
 * flight without the host having to serialize them.
 */
@Serializable
public sealed interface Frame {

    /** First frame a connecting device sends. */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val protocolVersion: Int,
        /** A label the human chose, to tell two phones apart. Never a hardware identifier. */
        val device: String,
        val sdkVersion: String,
    ) : Frame

    /** The host accepted the device. */
    @Serializable
    @SerialName("welcome")
    public data class Welcome(
        val protocolVersion: Int,
        val host: String,
    ) : Frame

    /** The host asks what the device can do. */
    @Serializable
    @SerialName("tools_request")
    public data class ToolsRequest(val id: String) : Frame

    /** The device's answer, derived from `ToolCodec.toolDescriptors()`. */
    @Serializable
    @SerialName("tools_response")
    public data class ToolsResponse(val id: String, val tools: List<ToolSpec>) : Frame

    /** Run one action. [payload] is a `ToolCodec` action document. */
    @Serializable
    @SerialName("action_request")
    public data class ActionRequest(val id: String, val payload: String) : Frame

    /** The action's outcome. [payload] is a `ToolCodec` result document. */
    @Serializable
    @SerialName("action_response")
    public data class ActionResponse(val id: String, val payload: String) : Frame

    /**
     * An `AgentEvent`, forwarded live.
     *
     * The host uses these to show what the phone is doing between actions and to feed the
     * same records to telemetry. They are notifications: no id, no response.
     */
    @Serializable
    @SerialName("event")
    public data class Event(val payload: String) : Frame

    /**
     * Something went wrong at the transport level.
     *
     * Note what does *not* arrive here: a failed action. Those are ordinary
     * [ActionResponse]s carrying a `Failure`, because the SDK treats every foreseeable
     * problem as a result rather than an exception and the wire must not undo that.
     */
    @Serializable
    @SerialName("error")
    public data class Error(val id: String? = null, val message: String) : Frame
}

/**
 * One capability, in the shape the host needs to advertise it.
 *
 * A near-copy of the SDK's `ToolDescriptor`, and deliberately a copy: this module cannot
 * depend on the SDK, and pinning the wire shape here means a change to the SDK's internal
 * descriptor does not silently become a protocol change.
 */
@Serializable
public data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments. */
    val parameterSchema: JsonObject,
    /**
     * The highest risk this tool can carry, as a lowercase name.
     *
     * Advisory only. The phone enforces its own `SafetyPolicy` and a host cannot negotiate
     * it upward -- this exists so a model can be told up front that a tool is dangerous,
     * not so the host can decide whether it is.
     */
    val maxRisk: String,
)

/** The JSON configuration used for every frame. */
public object ProtocolJson {
    public val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    public fun encode(frame: Frame): String = instance.encodeToString(Frame.serializer(), frame)

    public fun decode(text: String): Frame = instance.decodeFromString(Frame.serializer(), text)
}
