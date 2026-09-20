package com.andropilot.host.agent

import com.andropilot.host.AgentBridge
import com.andropilot.host.Skills
import com.andropilot.protocol.Frame
import com.andropilot.protocol.PROTOCOL_VERSION
import com.andropilot.protocol.ProtocolJson
import com.andropilot.protocol.ToolSpec
import com.andropilot.protocol.ws.WsClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the loop against a scripted model and a stand-in device.
 *
 * Both ends are fakes, but the middle is real: frames cross a real socket and the loop is
 * the shipping one. What is being checked is the part that has to be right before this
 * touches anybody's phone -- that results get fed back, that a bad tool name does not end
 * the run, and that the step ceiling actually holds.
 */
class AgentLoopTest {

    /** Replies from a script, and records what it was asked. */
    private class ScriptedModel(private val replies: List<ModelReply>) : ModelClient {
        val requests = CopyOnWriteArrayList<List<Turn>>()
        var toolsSeen: List<ToolSpec> = emptyList()
        override val describe: String get() = "scripted"
        override fun complete(turns: List<Turn>, tools: List<ToolSpec>): ModelReply {
            requests += turns.toList()
            toolsSeen = tools
            return replies.getOrElse(requests.size - 1) { replies.last() }
        }
    }

    private class FakeDevice(
        port: Int,
        token: String,
        private val tools: List<ToolSpec>,
        private val answer: (String) -> Pair<String, String>,
    ) : AutoCloseable {
        private val connection = WsClient.connect(
            "ws://127.0.0.1:$port/agent",
            headers = mapOf("Authorization" to "Bearer $token"),
            readTimeoutMs = 10_000,
        )
        val actions = CopyOnWriteArrayList<String>()
        val notes = CopyOnWriteArrayList<Frame.Note>()

        init {
            Thread {
                connection.send(ProtocolJson.encode(Frame.Hello(PROTOCOL_VERSION, "fake", "0.1.0")))
                while (true) {
                    val text = connection.receive() ?: return@Thread
                    when (val frame = runCatching { ProtocolJson.decode(text) }.getOrNull()) {
                        is Frame.ToolsRequest ->
                            connection.send(ProtocolJson.encode(Frame.ToolsResponse(frame.id, tools)))
                        is Frame.ActionRequest -> {
                            actions += frame.payload
                            val (payload, summary) = answer(frame.payload)
                            connection.send(
                                ProtocolJson.encode(Frame.ActionResponse(frame.id, payload, summary)),
                            )
                        }
                        is Frame.Note -> notes += frame
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

    private fun call(id: String, name: String, args: String = "{}") = ToolCall(id, name, args)

    @Test
    fun `runs the model's tool calls on the device and feeds the results back`(@TempDir dir: File) {
        val model = ScriptedModel(
            listOf(
                ModelReply("Looking at the screen.", listOf(call("1", "observe"))),
                ModelReply("Tapping Wi-Fi.", listOf(call("2", "click", """{"selector":"Wi-Fi"}"""))),
                ModelReply("Wi-Fi is open.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"), tool("click"))) {
                """{"type":"success"}""" to "OK, screen shown"
            }.use { device ->
                assertTrue(bridge.awaitDevice(5_000))
                val outcome = AgentLoop(bridge, model, Skills(dir), log = {}).run("open wifi")

                assertTrue(outcome.finished)
                assertEquals("Wi-Fi is open.", outcome.message)
                assertEquals(2, outcome.actions)

                // The arguments the model produced became the action, with the tool name
                // as the discriminator.
                assertEquals("""{"type":"observe"}""", device.actions[0])
                assertEquals("""{"type":"click","selector":"Wi-Fi"}""", device.actions[1])

                // The device's compact summary is what reached the model, not the full
                // result document -- a real one carries an entire snapshot.
                val lastRequest = model.requests.last()
                val results = lastRequest.filterIsInstance<Turn.ToolResult>()
                assertEquals(2, results.size)
                assertEquals("OK, screen shown", results[0].content)
                assertEquals("1", results[0].callId)
            }
        }
    }

    @Test
    fun `records what the model was trying to do, next to what happened`(@TempDir dir: File) {
        val model = ScriptedModel(
            listOf(
                ModelReply("Tapping Wi-Fi because the task asks for it.", listOf(call("1", "click"))),
                ModelReply("Done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("click"))) { """{"type":"success"}""" to "OK" }
                .use { device ->
                    assertTrue(bridge.awaitDevice(5_000))
                    AgentLoop(bridge, model, Skills(dir), log = {}).run("open wifi")
                    Thread.sleep(200)

                    // Intent is the one thing an action cannot tell you afterwards, and it
                    // is what makes a run reviewable rather than merely logged.
                    val intents = device.notes.filter { it.data["kind"] == "intent" }
                    assertEquals(1, intents.size)
                    assertEquals("Tapping Wi-Fi because the task asks for it.", intents[0].message)
                    assertTrue(device.notes.any { it.message.startsWith("Run started") })

                    // A plan and a conclusion are different claims, so they are recorded
                    // as different kinds rather than left to be told apart by position.
                    val conclusions = device.notes.filter { it.data["kind"] == "conclusion" }
                    assertEquals(1, conclusions.size)
                    assertEquals("Done.", conclusions[0].message)
                }
        }
    }

    @Test
    fun `tells the model when it invented a tool instead of ending the run`(@TempDir dir: File) {
        val model = ScriptedModel(
            listOf(
                ModelReply(null, listOf(call("1", "teleport"))),
                ModelReply("Understood.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) { """{"type":"success"}""" to "OK" }
                .use { device ->
                    assertTrue(bridge.awaitDevice(5_000))
                    val outcome = AgentLoop(bridge, model, Skills(dir), log = {}).run("go")

                    assertTrue(outcome.finished)
                    assertEquals(0, outcome.actions)
                    assertTrue(device.actions.isEmpty(), "an unknown tool must not reach the device")

                    // Answered, not dropped: a tool_call with no matching result makes the
                    // next request invalid, so silence here would end the run.
                    val result = model.requests.last().filterIsInstance<Turn.ToolResult>().single()
                    assertEquals("1", result.callId)
                    assertTrue(result.content.contains("no tool called 'teleport'"), result.content)
                    assertTrue(result.content.contains("observe"), "it should say what does exist")
                }
        }
    }

    @Test
    fun `stops at the step ceiling when the model will not finish`(@TempDir dir: File) {
        // A model that has misread a screen will keep trying the same thing. This runs
        // against somebody's actual phone, so the ceiling is not optional.
        val model = ScriptedModel(listOf(ModelReply("Trying again.", listOf(call("1", "click")))))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("click"))) {
                """{"type":"failure"}""" to "FAILED [no_effect] nothing changed"
            }.use {
                assertTrue(bridge.awaitDevice(5_000))
                val outcome = AgentLoop(bridge, model, Skills(dir), maxSteps = 3, log = {}).run("go")

                assertFalse(outcome.finished)
                assertEquals(3, outcome.steps)
                assertEquals(3, outcome.actions)
            }
        }
    }

    @Test
    fun `puts the app notes in front of the model`(@TempDir dir: File) {
        File(dir, "com.android.settings").mkdirs()
        File(dir, "com.android.settings/SKILL.md").writeText("Wi-Fi lives under Network & internet.")
        val model = ScriptedModel(listOf(ModelReply("Nothing to do.", emptyList())))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) { """{"type":"success"}""" to "OK" }
                .use {
                    assertTrue(bridge.awaitDevice(5_000))
                    AgentLoop(bridge, model, Skills(dir), log = {}).run("open wifi")

                    val system = model.requests.first().filterIsInstance<Turn.System>().single()
                    assertTrue(system.text.contains("Wi-Fi lives under Network & internet."))
                    assertTrue(system.text.contains("observe"), "the standing instructions should survive too")
                }
        }
    }

    @Test
    fun `refuses to start without a connected device`(@TempDir dir: File) {
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            val failure = runCatching {
                AgentLoop(bridge, ScriptedModel(emptyList()), Skills(dir), log = {}).run("go")
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("agent app"), failure.message)
        }
    }
}
