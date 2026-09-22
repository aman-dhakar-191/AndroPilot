package com.andropilot.host.ui

import com.andropilot.host.AgentBridge
import com.andropilot.host.Skills
import com.andropilot.host.agent.AgentLoop
import com.andropilot.host.agent.Catalog
import com.andropilot.host.agent.ModelOption
import com.andropilot.host.agent.ModelClient
import com.andropilot.host.agent.ModelReply
import com.andropilot.host.agent.ToolCall
import com.andropilot.host.agent.Turn
import com.andropilot.protocol.Frame
import com.andropilot.protocol.PROTOCOL_VERSION
import com.andropilot.protocol.ProtocolJson
import com.andropilot.protocol.ToolSpec
import com.andropilot.protocol.ws.WsClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Exercises the control page's server against a stand-in device and a scripted model.
 *
 * The browser is not simulated, but everything it depends on is: the event stream's framing,
 * the refusals it has to render, and the fact that a run started over HTTP reaches a real
 * phone connection.
 */
class ControlServerTest {

    private class ScriptedModel(private val replies: List<ModelReply>) : ModelClient {
        var calls = 0
        override val describe: String get() = "scripted"
        override fun complete(turns: List<Turn>, tools: List<ToolSpec>): ModelReply =
            replies.getOrElse(calls++) { replies.last() }
    }

    private class FakeDevice(port: Int, token: String, private val tools: List<ToolSpec>) : AutoCloseable {
        private val connection = WsClient.connect(
            "ws://127.0.0.1:$port/agent",
            headers = mapOf("Authorization" to "Bearer $token"),
            readTimeoutMs = 10_000,
        )

        init {
            Thread {
                connection.send(ProtocolJson.encode(Frame.Hello(PROTOCOL_VERSION, "fake", "0.1.0")))
                while (true) {
                    val text = connection.receive() ?: return@Thread
                    when (val frame = runCatching { ProtocolJson.decode(text) }.getOrNull()) {
                        is Frame.ToolsRequest ->
                            connection.send(ProtocolJson.encode(Frame.ToolsResponse(frame.id, tools)))
                        is Frame.ActionRequest -> connection.send(
                            ProtocolJson.encode(
                                Frame.ActionResponse(frame.id, """{"type":"success"}""", "OK ${frame.payload}"),
                            ),
                        )
                        else -> Unit
                    }
                }
            }.apply { isDaemon = true }.start()
        }

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

    /** Reads `data:` lines off the event stream on a background thread. */
    private class Stream(port: Int) : AutoCloseable {
        val lines = CopyOnWriteArrayList<String>()
        private val connection = (URI("http://127.0.0.1:$port/events").toURL().openConnection() as HttpURLConnection)
        private val thread: Thread

        init {
            connection.readTimeout = 20_000
            thread = Thread {
                runCatching {
                    val reader: BufferedReader = connection.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) lines += line.removePrefix("data: ")
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        fun await(seconds: Long = 10, predicate: (String) -> Boolean): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                lines.firstOrNull(predicate)?.let { return it }
                Thread.sleep(50)
            }
            throw AssertionError("No event matched within ${seconds}s. Saw: $lines")
        }

        override fun close() {
            connection.disconnect()
            thread.interrupt()
        }
    }

    private fun post(port: Int, path: String, body: String): Pair<Int, String> {
        val connection = (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        return code to text
    }

    @Test
    fun `serves a page that can drive a run`(@TempDir dir: File) {
        val model = ScriptedModel(
            listOf(
                ModelReply("Opening settings.", listOf(ToolCall("1", "observe", "{}"))),
                ModelReply("All done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            val bus = RunEventBus()
            ControlServer(0, bridge, bus, { AgentLoop(bridge, model, Skills(dir), log = {}, emit = bus::emit) })
                .start().use { server ->
                    FakeDevice(bridge.port, "t", listOf(tool("observe"))).use {
                        assertTrue(bridge.awaitDevice(5_000))

                        Stream(server.port).use { stream ->
                            // The page must know there is a phone before anyone presses Run.
                            assertTrue(stream.await { it.contains("\"type\":\"device\"") }.contains("\"connected\":true"))

                            val (code, _) = post(server.port, "/run", """{"goal":"open settings"}""")
                            assertEquals(202, code)

                            assertTrue(stream.await { it.contains("\"type\":\"started\"") }.contains("open settings"))
                            assertTrue(stream.await { it.contains("\"type\":\"intent\"") }.contains("Opening settings."))
                            assertTrue(stream.await { it.contains("\"type\":\"action\"") }.contains("observe"))
                            // The device's own summary reaches the page, not the raw result.
                            assertTrue(stream.await { it.contains("\"type\":\"result\"") }.contains("OK"))
                            assertTrue(stream.await { it.contains("\"type\":\"finished\"") }.contains("All done."))
                        }
                    }
                }
        }
    }

    @Test
    fun `refuses a run with a reason the page can show`(@TempDir dir: File) {
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            val bus = RunEventBus()

            // No model configured: the UI is still useful for watching, and has to say why
            // it cannot start rather than failing silently.
            ControlServer(0, bridge, bus, null).start().use { server ->
                val (code, body) = post(server.port, "/run", """{"goal":"do a thing"}""")
                assertEquals(409, code)
                assertTrue(body.contains("--model-endpoint"), body)
            }

            val model = ScriptedModel(listOf(ModelReply("done", emptyList())))
            ControlServer(0, bridge, bus, { AgentLoop(bridge, model, Skills(dir), log = {}) })
                .start().use { server ->
                    // No device attached.
                    val (noDevice, body) = post(server.port, "/run", """{"goal":"do a thing"}""")
                    assertEquals(409, noDevice)
                    assertTrue(body.contains("No device"), body)

                    FakeDevice(bridge.port, "t", listOf(tool("observe"))).use {
                        assertTrue(bridge.awaitDevice(5_000))
                        val (empty, emptyBody) = post(server.port, "/run", """{"goal":"   "}""")
                        assertEquals(400, empty)
                        assertTrue(emptyBody.contains("what the phone should do"), emptyBody)
                    }
                }
        }
    }

    @Test
    fun `offers the endpoint's model list and runs the one the page picked`(@TempDir dir: File) {
        // The names a gateway answers to are typed into its dashboard, so the page has to
        // be told them rather than have somebody guess the spelling one failed run at a time.
        val asked = java.util.concurrent.atomic.AtomicReference<String?>("unset")
        val model = ScriptedModel(listOf(ModelReply("done", emptyList())))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            val bus = RunEventBus()
            ControlServer(
                0,
                bridge,
                bus,
                { chosen -> asked.set(chosen); AgentLoop(bridge, model, Skills(dir), log = {}, emit = bus::emit) },
                modelCatalog = {
                    Catalog(
                        listOf(
                            ModelOption("AndroPilot", "combo"),
                            ModelOption("combo/AndroPilot", "combo"),
                            ModelOption("openai/gpt-5", "model"),
                        ),
                        combosListed = true,
                    )
                },
                configuredModel = "AndroPilot",
            ).start().use { server ->
                val (code, body) = get(server.port, "/models")
                assertEquals(200, code)
                assertTrue(body.contains(""""id":"AndroPilot","group":"combo""""), body)
                assertTrue(body.contains(""""id":"openai/gpt-5","group":"model""""), body)
                assertTrue(body.contains(""""combosListed":true"""), body)
                assertTrue(body.contains(""""selected":"AndroPilot""""), body)

                FakeDevice(bridge.port, "t", listOf(tool("observe"))).use {
                    assertTrue(bridge.awaitDevice(5_000))
                    assertEquals(202, post(server.port, "/run", """{"goal":"go","model":"combo/AndroPilot"}""").first)
                    assertEquals("combo/AndroPilot", asked.get())
                }
            }
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection)
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        return code to text
    }

    @Test
    fun `stops a run that will not finish on its own`(@TempDir dir: File) {
        // A model that keeps calling tools forever is the case the Stop button exists for.
        val model = ScriptedModel(listOf(ModelReply("Trying.", listOf(ToolCall("1", "observe", "{}")))))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            val bus = RunEventBus()
            ControlServer(
                0,
                bridge,
                bus,
                { AgentLoop(bridge, model, Skills(dir), maxSteps = 500, log = {}, emit = bus::emit) },
            ).start().use { server ->
                FakeDevice(bridge.port, "t", listOf(tool("observe"))).use {
                    assertTrue(bridge.awaitDevice(5_000))
                    Stream(server.port).use { stream ->
                        assertEquals(202, post(server.port, "/run", """{"goal":"forever"}""").first)
                        stream.await { it.contains("\"type\":\"action\"") }

                        assertEquals(200, post(server.port, "/stop", "{}").first)
                        val halted = stream.await(seconds = 15) { it.contains("\"type\":\"halted\"") }
                        assertTrue(halted.contains("Stopped."), halted)
                    }
                }
            }
        }
    }

    @Test
    fun `serves the page itself`() {
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            ControlServer(0, bridge, RunEventBus(), null).start().use { server ->
                val body = URI("http://127.0.0.1:${server.port}/").toURL()
                    .openStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(body.contains("<title>AndroPilot</title>"), "the UI resource is not on the classpath")
                assertTrue(body.contains("EventSource"))
            }
        }
    }
}
