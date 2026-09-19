package com.andropilot.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer

/**
 * Exposes the connected phone as an MCP server, over stdio.
 *
 * This runs on the PC, not on the phone, and that is not an implementation shortcut: an
 * MCP server is a local process a client spawns or an HTTP endpoint it calls, and a device
 * behind carrier NAT is neither. Putting the server where the client already is means any
 * MCP-speaking runtime drives the phone with no Android work at all.
 *
 * The mapping is nearly free because the SDK already describes its capabilities in neutral
 * terms: `ToolCodec.toolDescriptors()` gives a name, a description and a JSON Schema, which
 * is exactly an MCP tool definition. `tools/call` forwards the arguments to the device as
 * an action document and returns whatever comes back.
 *
 * `ToolSpec.maxRisk` has no MCP equivalent, so it is written into the description. It stays
 * advisory: the phone enforces its own `SafetyPolicy`, and a host that could raise its own
 * privileges over the wire would make the whole safety model decorative.
 */
public class McpServer(
    private val bridge: AgentBridge,
    private val skills: Skills,
    private val version: String = "0.1.0",
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Reads requests until the input ends. One JSON-RPC message per line. */
    public fun serve(input: BufferedReader, output: Writer) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val response = handleLine(line) ?: continue
            synchronized(output) {
                output.write(response)
                output.write("\n")
                output.flush()
            }
        }
    }

    internal fun handleLine(line: String): String? {
        val request = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return error(JsonNull, -32700, "Parse error: ${e.message}")
        }
        val id = request["id"] ?: JsonNull
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return error(id, -32600, "Missing method")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        // Notifications carry no id and must produce no response at all -- answering one is
        // a protocol violation that some clients treat as fatal.
        if (request["id"] == null) return null

        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, toolsList())
                "tools/call" -> result(id, toolsCall(params))
                "resources/list" -> result(id, resourcesList())
                "resources/read" -> result(id, resourcesRead(params))
                else -> error(id, -32601, "Unknown method '$method'")
            }
        } catch (e: Exception) {
            error(id, -32603, e.message ?: e::class.java.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject = buildJsonObject {
        // Echo the client's requested revision rather than asserting one. The client knows
        // which revisions it speaks; guessing wrong here fails the handshake outright.
        put(
            "protocolVersion",
            params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: DEFAULT_PROTOCOL_VERSION,
        )
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", true) }
            putJsonObject("resources") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", "andropilot-host")
            put("version", version)
        }
        put(
            "instructions",
            "Drives a physical Android device over an accessibility service. Call `observe` " +
                "before acting and after anything that may have changed the screen; element " +
                "ids are only valid for the snapshot that produced them. Every tool reports " +
                "success or a machine-readable failure reason -- read it rather than " +
                "retrying blindly. Some actions need a human to approve them on the device.",
        )
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (tool in bridge.tools()) {
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description + " (risk: ${tool.maxRisk})")
                        put("inputSchema", tool.parameterSchema)
                    },
                )
            }
            // Present even with no device attached, so a client that lists tools before the
            // phone connects still sees something actionable instead of an empty server.
            add(
                buildJsonObject {
                    put("name", DEVICE_STATUS)
                    put("description", "Report whether an Android device is connected to this host, and which.")
                    put("inputSchema", buildJsonObject { put("type", "object"); putJsonObject("properties") {} })
                },
            )
        }
    }

    private fun toolsCall(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return text("No tool name was given.", isError = true)
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        if (name == DEVICE_STATUS) {
            val device = bridge.device
            return text(
                if (device == null) {
                    "No device is connected. Open the AndroPilot agent app, point it at this " +
                        "host, and connect."
                } else {
                    "Connected: ${device.name} (SDK ${device.sdkVersion}) from ${device.remoteAddress}, " +
                        "${bridge.tools().size} tools available."
                },
            )
        }

        // The device speaks the SDK's action documents: a "type" discriminator plus the
        // action's own fields. An MCP argument object is already that, minus the name.
        val payload = buildJsonObject {
            put("type", name)
            arguments.forEach { (key, value) -> put(key, value) }
        }.toString()

        val resultPayload = try {
            bridge.execute(payload)
        } catch (e: Exception) {
            return text(e.message ?: "The device could not be reached.", isError = true)
        }

        val notes = skills.forPackage(packageOf(resultPayload))?.notes
        return text(if (notes == null) resultPayload else resultPayload + "\n\n--- app notes ---\n" + notes)
    }

    /**
     * Finds the package a result describes, so the matching app notes can ride along.
     *
     * Parsed generically rather than through the SDK's model: the host deliberately does not
     * depend on core, and a result it cannot fully parse must still be forwarded intact.
     */
    private fun packageOf(payload: String): String? = runCatching {
        fun search(element: JsonElement): String? = when (element) {
            is JsonObject -> element["package_name"]?.jsonPrimitive?.contentOrNull
                ?: element.values.firstNotNullOfOrNull(::search)
            is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull(::search)
            else -> null
        }
        search(json.parseToJsonElement(payload))
    }.getOrNull()

    private fun resourcesList(): JsonObject = buildJsonObject {
        putJsonArray("resources") {
            for (skill in skills.all()) {
                add(
                    buildJsonObject {
                        put("uri", "andropilot://skill/${skill.packageName}")
                        put("name", "App notes: ${skill.packageName}")
                        put("description", "Hand-written guidance for driving ${skill.packageName}.")
                        put("mimeType", "text/markdown")
                    },
                )
            }
        }
    }

    private fun resourcesRead(params: JsonObject): JsonObject {
        val uri = params["uri"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("No uri was given.")
        val packageName = uri.removePrefix("andropilot://skill/")
        val skill = skills.forPackage(packageName)
            ?: throw IllegalArgumentException("No app notes exist for '$packageName'.")
        return buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("uri", uri)
                        put("mimeType", "text/markdown")
                        put("text", skill.notes)
                    },
                )
            }
        }
    }

    private fun text(body: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", body) })
        }
        if (isError) put("isError", true)
    }

    private fun result(id: JsonElement, payload: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", payload)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    internal companion object {
        /** Used only when a client does not state one. */
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        const val DEVICE_STATUS = "device_status"
    }
}
