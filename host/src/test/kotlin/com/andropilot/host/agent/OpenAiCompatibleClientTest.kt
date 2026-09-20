package com.andropilot.host.agent

import com.andropilot.protocol.ToolSpec
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the client against a stub endpoint on loopback.
 *
 * The request shape is the whole risk here: a tool definition in the wrong place, or a
 * tool result that does not name the call it answers, is rejected by the endpoint rather
 * than quietly degraded. That only shows up in bytes actually sent, so they are.
 */
class OpenAiCompatibleClientTest {

    private lateinit var server: HttpServer
    private val received = AtomicReference<JsonObject>()
    private val authorization = AtomicReference<String>()
    private var responseBody: String = ""
    private var status: Int = 200

    private fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            received.set(
                Json.parseToJsonElement(
                    exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                ).jsonObject,
            )
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun client() = OpenAiCompatibleClient(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        apiKey = "sk-test",
        model = "local-model",
    )

    private val tool = ToolSpec(
        name = "click",
        description = "Tap an element.",
        parameterSchema = buildJsonObject { put("type", "object") },
        maxRisk = "mutating",
    )

    @Test
    fun `sends tools and messages in the chat-completions shape`() {
        responseBody = """{"choices":[{"message":{"content":"done","tool_calls":[]}}]}"""
        start()
        client().complete(
            listOf(Turn.System("rules"), Turn.User("open wifi")),
            listOf(tool),
        )

        val request = received.get()
        assertEquals("Bearer sk-test", authorization.get())
        assertEquals("local-model", request["model"]!!.jsonPrimitive.content)

        val messages = request["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("open wifi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        val function = request["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("click", function["name"]!!.jsonPrimitive.content)
        assertTrue(function.containsKey("parameters"))
        // The risk level has nowhere else to go in this shape, so it rides in the text.
        assertTrue(function["description"]!!.jsonPrimitive.content.contains("risk: mutating"))
    }

    @Test
    fun `reads tool calls out of the reply`() {
        responseBody = """
            {"choices":[{"message":{"content":"Tapping Wi-Fi.","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"click","arguments":"{\"selector\":\"Wi-Fi\"}"}}
            ]}}]}
        """.trimIndent()
        start()
        val reply = client().complete(listOf(Turn.User("x")), listOf(tool))

        assertEquals("Tapping Wi-Fi.", reply.text)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("call_1", reply.toolCalls[0].id)
        assertEquals("click", reply.toolCalls[0].name)
        assertEquals("""{"selector":"Wi-Fi"}""", reply.toolCalls[0].argumentsJson)
        assertTrue(!reply.isFinal)
    }

    @Test
    fun `a reply with no tool calls ends the run`() {
        responseBody = """{"choices":[{"message":{"content":"Wi-Fi is already on."}}]}"""
        start()
        val reply = client().complete(listOf(Turn.User("x")), listOf(tool))
        assertTrue(reply.isFinal)
        assertEquals("Wi-Fi is already on.", reply.text)
    }

    @Test
    fun `empty arguments become an empty object rather than breaking the run`() {
        responseBody = """
            {"choices":[{"message":{"tool_calls":[
              {"id":"c","type":"function","function":{"name":"observe","arguments":""}}
            ]}}]}
        """.trimIndent()
        start()
        val reply = client().complete(listOf(Turn.User("x")), listOf(tool))
        assertEquals("{}", reply.toolCalls[0].argumentsJson)
        assertNull(reply.text)
    }

    @Test
    fun `sends an assistant turn with its tool calls and the matching results`() {
        responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        start()
        client().complete(
            listOf(
                Turn.User("x"),
                Turn.Assistant("tapping", listOf(ToolCall("call_1", "click", """{"selector":"Wi-Fi"}"""))),
                Turn.ToolResult("call_1", "click", "OK click"),
            ),
            listOf(tool),
        )

        val messages = received.get()["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        // A tool_call with no answering tool message is rejected outright by the endpoint,
        // so the pairing is not cosmetic.
        val result = messages[2].jsonObject
        assertEquals("tool", result["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", result["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("OK click", result["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `surfaces what the endpoint objected to`() {
        responseBody = """{"error":{"message":"model 'local-model' not found"}}"""
        status = 404
        start()
        val failure = assertThrows<ModelException> {
            client().complete(listOf(Turn.User("x")), listOf(tool))
        }
        // The endpoint's own words, not a generic failure: this is where an unknown model
        // or a rejected schema actually gets explained.
        assertTrue(failure.message!!.contains("model 'local-model' not found"), failure.message)
    }

    @Test
    fun `reports an unreachable endpoint rather than throwing something opaque`() {
        start()
        val port = server.address.port
        server.stop(0)
        val failure = assertThrows<ModelException> {
            OpenAiCompatibleClient("http://127.0.0.1:$port/v1", "k", "m")
                .complete(listOf(Turn.User("x")), listOf(tool))
        }
        assertTrue(failure.message!!.contains("Could not reach"))
    }

    @Test
    fun `merges the tool name and arguments into an action document`() {
        assertEquals(
            """{"type":"click","selector":"Wi-Fi"}""",
            actionPayload("click", """{"selector":"Wi-Fi"}""", Json),
        )
        // A model that produced unusable arguments still gets a well-formed action, which
        // the device answers with a structured failure it can read and correct.
        assertEquals("""{"type":"observe"}""", actionPayload("observe", "not json", Json))
    }
}
