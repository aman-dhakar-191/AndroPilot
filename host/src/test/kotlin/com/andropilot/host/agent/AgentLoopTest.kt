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
    fun `an app's notes arrive with the first result from it, once`(@TempDir dir: File) {
        // The index in the prompt says notes exist; this is how they actually arrive. Once,
        // because repeating a page of notes on every turn crowds out the screen itself.
        File(dir, "com.whatsapp").mkdirs()
        File(dir, "com.whatsapp/SKILL.md").writeText("# WhatsApp\nThe send button is unlabelled.")
        val model = ScriptedModel(
            listOf(
                ModelReply("Look.", listOf(call("1", "observe"))),
                ModelReply("Look again.", listOf(call("2", "observe"))),
                ModelReply("Done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) {
                """{"type":"success","package_name":"com.whatsapp"}""" to "OK, screen shown"
            }.use {
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}).run("send a message")

                val results = model.requests.last().filterIsInstance<Turn.ToolResult>()
                assertTrue(
                    results[0].content.contains("The send button is unlabelled."),
                    "the first result from the app should carry its notes: ${results[0].content}",
                )
                assertFalse(
                    results[1].content.contains("The send button is unlabelled."),
                    "the notes should not repeat on every later result",
                )
            }
        }
    }

    @Test
    fun `an app with no notes gets the unknown-app playbook`(@TempDir dir: File) {
        // The uncovered app is the common case -- a phone holds a hundred and a dozen have
        // notes -- so it must not be the case that gets no help.
        File(dir, "_unknown").mkdirs()
        File(dir, "_unknown/SKILL.md").writeText("# Any app\nObserve before assuming a shape.")
        val model = ScriptedModel(
            listOf(
                ModelReply("Look.", listOf(call("1", "observe"))),
                ModelReply("Done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) {
                """{"type":"success","package_name":"com.example.nobody.wrote.notes"}""" to "OK"
            }.use {
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}).run("do a thing")

                val results = model.requests.last().filterIsInstance<Turn.ToolResult>()
                assertTrue(
                    results[0].content.contains("Observe before assuming a shape."),
                    "an unknown app should still get the playbook: ${results[0].content}",
                )
            }
        }
    }

    @Test
    fun `the prompt indexes the app notes rather than carrying them`(@TempDir dir: File) {
        // A library of notes that all sat in the prompt would cost the whole library on
        // every turn to help with the one app a run opens, and get worse with each skill.
        File(dir, "com.whatsapp").mkdirs()
        File(dir, "com.whatsapp/SKILL.md").writeText("# WhatsApp\nThe send button is unlabelled.")
        val model = ScriptedModel(listOf(ModelReply("Nothing to do.", emptyList())))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) {
                """{"type":"success"}""" to "OK"
            }.use {
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}).run("nothing")

                val system = model.requests.last().filterIsInstance<Turn.System>().single().text
                assertTrue(system.contains("com.whatsapp -- WhatsApp"), system)
                assertFalse(system.contains("The send button is unlabelled."), system)
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
    fun `a device that is merely slow is not reported as disconnected`(@TempDir dir: File) {
        // Collapsing a timeout into "not connected" told the model the phone was gone when
        // it was busy, so a launch that took a moment read as a dead device and the next
        // move was to go hunting instead of looking at the screen it had just opened.
        val model = ScriptedModel(
            listOf(
                ModelReply("Launching.", listOf(call("1", "launch_app", """{"package_name":"com.slow"}"""))),
                ModelReply("Noted.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            // Never answers, so the wait expires while the device is still attached.
            // Answers, but long after the loop has stopped waiting.
            FakeDevice(bridge.port, "t", listOf(tool("launch_app"))) {
                Thread.sleep(1_500)
                """{"type":"success"}""" to "OK"
            }.use {
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}, actionTimeoutMs = 300).run("open it")

                val content = model.requests.last().filterIsInstance<Turn.ToolResult>().single().content
                assertTrue(content.startsWith("FAILED [timeout]"), content)
                assertTrue(content.contains("observe"), "it should say what to do instead: $content")
            }
        }
    }

    @Test
    fun `the installed app list is produced once per run`(@TempDir dir: File) {
        // Re-listing is the reflex after any failure, and it is eighty-odd lines of the
        // most stable information on the device.
        val model = ScriptedModel(
            listOf(
                ModelReply("What is installed?", listOf(call("1", "list_apps"))),
                ModelReply("Checking again.", listOf(call("2", "list_apps"))),
                ModelReply("Done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("list_apps"))) {
                """{"type":"success"}""" to "OK list_apps\napps (2):\n  - Clock = com.android.deskclock"
            }.use { device ->
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}).run("find the clock")

                assertEquals(1, device.actions.size, "the phone should be asked only once")
                val results = model.requests.last().filterIsInstance<Turn.ToolResult>()
                assertTrue(results[0].content.contains("Clock = com.android.deskclock"))
                assertTrue(results[1].content.contains("have not changed"), results[1].content)
                assertFalse(results[1].content.contains("com.android.deskclock"), "no second copy")
            }
        }
    }

    @Test
    fun `an app that is not installed re-reads the list`(@TempDir dir: File) {
        // The one thing that genuinely invalidates it. Caching without this would strand a
        // run on a stale list after the user installed the app it was asking for.
        val model = ScriptedModel(
            listOf(
                ModelReply("What is installed?", listOf(call("1", "list_apps"))),
                ModelReply("Launching.", listOf(call("2", "launch_app", """{"package_name":"com.nope"}"""))),
                ModelReply("Listing again.", listOf(call("3", "list_apps"))),
                ModelReply("Done.", emptyList()),
            ),
        )
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("list_apps"), tool("launch_app"))) { action ->
                if (action.contains("launch_app")) {
                    """{"type":"failure"}""" to "FAILED [app_unavailable] No such package."
                } else {
                    """{"type":"success"}""" to "OK list_apps\napps (1):\n  - Clock = com.android.deskclock"
                }
            }.use { device ->
                assertTrue(bridge.awaitDevice(5_000))
                AgentLoop(bridge, model, Skills(dir), log = {}).run("open nope")

                assertEquals(3, device.actions.size, "the failed launch should invalidate the list")
                val results = model.requests.last().filterIsInstance<Turn.ToolResult>()
                assertTrue(results[2].content.contains("Clock = com.android.deskclock"), results[2].content)
            }
        }
    }

    @Test
    fun `puts the general guidance in front of the model`(@TempDir dir: File) {
        // Unlike an app's notes, this applies on every screen of every run, so it belongs
        // in the prompt rather than arriving with a result from one particular app.
        File(dir, "_global").mkdirs()
        File(dir, "_global/SKILL.md").writeText("Observe again after every navigation.")
        val model = ScriptedModel(listOf(ModelReply("Nothing to do.", emptyList())))
        AgentBridge(port = 0, token = "t").start().use { bridge ->
            FakeDevice(bridge.port, "t", listOf(tool("observe"))) { """{"type":"success"}""" to "OK" }
                .use {
                    assertTrue(bridge.awaitDevice(5_000))
                    AgentLoop(bridge, model, Skills(dir), log = {}).run("open wifi")

                    val system = model.requests.first().filterIsInstance<Turn.System>().single()
                    assertTrue(system.text.contains("Observe again after every navigation."))
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
