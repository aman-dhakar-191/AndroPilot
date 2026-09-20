package com.andropilot.host

import com.andropilot.protocol.Frame
import com.andropilot.protocol.PROTOCOL_VERSION
import com.andropilot.protocol.ProtocolJson
import com.andropilot.protocol.ToolSpec
import com.andropilot.protocol.ws.WsClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the host against a stand-in device.
 *
 * The fake device is a real WebSocket client speaking the real frames, so these tests cover
 * the handshake, the correlation of a response to its request, and the MCP translation --
 * everything except the accessibility service itself, which is the one part that needs
 * hardware.
 */
class HostTest {

    /** A device that answers every action with a fixed payload. */
    private class FakeDevice(
        port: Int,
        token: String,
        private val tools: List<ToolSpec>,
        /** Pushed unprompted once the handshake is done, the way real AgentEvents are. */
        private val events: List<String> = emptyList(),
        private val answer: (String) -> String,
    ) : AutoCloseable {
        private val connection = WsClient.connect(
            "ws://127.0.0.1:$port/agent",
            headers = mapOf("Authorization" to "Bearer $token"),
            readTimeoutMs = 10_000,
        )
        val lastRequest = AtomicReference<String>()

        val thread = Thread {
            connection.send(ProtocolJson.encode(Frame.Hello(PROTOCOL_VERSION, "fake", "0.1.0")))
            while (true) {
                val text = connection.receive() ?: return@Thread
                when (val frame = runCatching { ProtocolJson.decode(text) }.getOrNull()) {
                    is Frame.ToolsRequest -> {
                        connection.send(ProtocolJson.encode(Frame.ToolsResponse(frame.id, tools)))
                        events.forEach { connection.send(ProtocolJson.encode(Frame.Event(it))) }
                    }
                    is Frame.ActionRequest -> {
                        lastRequest.set(frame.payload)
                        connection.send(ProtocolJson.encode(Frame.ActionResponse(frame.id, answer(frame.payload))))
                    }
                    else -> Unit
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            connection.close()
        }
    }

    private fun tool(name: String) = ToolSpec(
        name = name,
        description = "Does $name.",
        parameterSchema = buildJsonObject { put("type", "object") },
        maxRisk = "read_only",
    )

    private fun bridge(onEvent: (String) -> Unit = {}) =
        AgentBridge(port = 0, token = "secret", onEvent = onEvent).start()

    @Test
    fun `forwards an action to the device and returns its result`() {
        bridge().use { bridge ->
            FakeDevice(bridge.port, "secret", listOf(tool("observe"))) { """{"type":"success"}""" }.use {
                assertTrue(bridge.awaitDevice(5_000))
                assertEquals("""{"type":"success"}""", bridge.execute("""{"type":"observe"}""").payload)
                assertEquals("""{"type":"observe"}""", it.lastRequest.get())
                assertEquals("fake", bridge.device?.name)
            }
        }
    }

    @Test
    fun `refuses a device with the wrong token`() {
        bridge().use { bridge ->
            val failure = runCatching {
                WsClient.connect("ws://127.0.0.1:${bridge.port}/agent", mapOf("Authorization" to "Bearer wrong"))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(bridge.device == null)
        }
    }

    @Test
    fun `reports a missing device instead of hanging`() {
        bridge().use { bridge ->
            val failure = runCatching { bridge.execute("""{"type":"observe"}""") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("No device is connected"))
        }
    }

    @Test
    fun `forwards device events`() {
        val events = java.util.concurrent.ArrayBlockingQueue<String>(4)
        bridge(onEvent = { events.put(it) }).use { bridge ->
            // The device pushes events unprompted -- one per AgentEvent -- so the host can
            // show what the phone is doing between actions and feed the same records to
            // telemetry. They are notifications: no id, no response.
            FakeDevice(
                bridge.port,
                "secret",
                listOf(tool("observe")),
                events = listOf("""{"kind":"note"}"""),
            ) { """{"type":"success"}""" }.use {
                assertTrue(bridge.awaitDevice(5_000))
                assertEquals("""{"kind":"note"}""", events.poll(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `turns device tools into mcp tools`(@TempDir dir: File) {
        bridge().use { bridge ->
            FakeDevice(bridge.port, "secret", listOf(tool("observe"), tool("click"))) { """{"type":"success"}""" }.use {
                assertTrue(bridge.awaitDevice(5_000))
                val mcp = McpServer(bridge, Skills(dir))
                val tools = mcp.call("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
                val names = tools.map { t -> t.jsonObject["name"]!!.jsonPrimitive.content }
                assertEquals(listOf("observe", "click", McpServer.DEVICE_STATUS), names)
                assertTrue(
                    tools[0].jsonObject["description"]!!.jsonPrimitive.content.contains("risk: read_only"),
                    "the risk level has to reach the model somehow, since MCP has no field for it",
                )
            }
        }
    }

    @Test
    fun `calls a tool and passes the arguments through as an action`(@TempDir dir: File) {
        bridge().use { bridge ->
            FakeDevice(bridge.port, "secret", listOf(tool("click"))) { """{"type":"success"}""" }.use { device ->
                assertTrue(bridge.awaitDevice(5_000))
                val mcp = McpServer(bridge, Skills(dir))
                val response = mcp.call(
                    "tools/call",
                    buildJsonObject {
                        put("name", "click")
                        put("arguments", buildJsonObject { put("selector", "Wi-Fi") })
                    },
                )
                val text = response["result"]!!.jsonObject["content"]!!.jsonArray[0]
                    .jsonObject["text"]!!.jsonPrimitive.content
                assertEquals("""{"type":"success"}""", text)
                // The MCP argument object becomes the action document, with the tool name
                // as the discriminator. No second schema, nothing to keep in step.
                assertEquals("""{"type":"click","selector":"Wi-Fi"}""", device.lastRequest.get())
            }
        }
    }

    @Test
    fun `reports a missing device as a tool error rather than a transport failure`(@TempDir dir: File) {
        bridge().use { bridge ->
            val mcp = McpServer(bridge, Skills(dir))
            val result = mcp.call(
                "tools/call",
                buildJsonObject { put("name", "click"); put("arguments", buildJsonObject {}) },
            )["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `answers device_status with no device attached`(@TempDir dir: File) {
        bridge().use { bridge ->
            val mcp = McpServer(bridge, Skills(dir))
            val text = mcp.call(
                "tools/call",
                buildJsonObject { put("name", McpServer.DEVICE_STATUS); put("arguments", buildJsonObject {}) },
            )["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("No device is connected"))
        }
    }

    @Test
    fun `never answers a notification`(@TempDir dir: File) {
        bridge().use { bridge ->
            val mcp = McpServer(bridge, Skills(dir))
            assertNull(mcp.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        }
    }

    @Test
    fun `echoes the protocol version the client asked for`(@TempDir dir: File) {
        bridge().use { bridge ->
            val mcp = McpServer(bridge, Skills(dir))
            val result = mcp.call("initialize", buildJsonObject { put("protocolVersion", "2099-01-01") })["result"]!!
            assertEquals("2099-01-01", result.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `attaches app notes to a result from that app`(@TempDir dir: File) {
        File(dir, "com.android.settings").mkdirs()
        File(dir, "com.android.settings/SKILL.md").writeText("Wi-Fi lives under Network & internet.")
        bridge().use { bridge ->
            val answer = """{"type":"success","snapshot":{"package_name":"com.android.settings"}}"""
            FakeDevice(bridge.port, "secret", listOf(tool("observe"))) { answer }.use {
                assertTrue(bridge.awaitDevice(5_000))
                val mcp = McpServer(bridge, Skills(dir))
                val text = mcp.call(
                    "tools/call",
                    buildJsonObject { put("name", "observe"); put("arguments", buildJsonObject {}) },
                )["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                assertTrue(text.contains("Wi-Fi lives under Network & internet."), text)
            }
        }
    }

    @Test
    fun `lists app notes as resources`(@TempDir dir: File) {
        File(dir, "com.whatsapp").mkdirs()
        File(dir, "com.whatsapp/SKILL.md").writeText("The send button is unlabelled.")
        bridge().use { bridge ->
            val mcp = McpServer(bridge, Skills(dir))
            val resources = mcp.call("resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray
            assertEquals("andropilot://skill/com.whatsapp", resources[0].jsonObject["uri"]!!.jsonPrimitive.content)

            val read = mcp.call("resources/read", buildJsonObject { put("uri", "andropilot://skill/com.whatsapp") })
            val text = read["result"]!!.jsonObject["contents"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertEquals("The send button is unlabelled.", text)
        }
    }

    @Test
    fun `parses the command line`() {
        val options = parse(arrayOf("--port", "9000", "--mcp", "--token", "t", "--ingest-port", "9100"))
        assertEquals(9000, options.port)
        assertEquals("t", options.token)
        assertEquals(9100, options.ingestPort)
        assertTrue(options.mcp)
        // Loopback unless asked otherwise: a wider bind exposes a socket that drives a phone.
        assertEquals("127.0.0.1", options.bind)
    }

    private fun McpServer.call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }
        return Json.parseToJsonElement(handleLine(request.toString())!!).jsonObject
    }
}
