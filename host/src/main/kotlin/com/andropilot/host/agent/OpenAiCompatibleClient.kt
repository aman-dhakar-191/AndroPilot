package com.andropilot.host.agent

import com.andropilot.protocol.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Talks to any endpoint that speaks the OpenAI chat-completions shape.
 *
 * Which is most self-hosted routers and gateways, so this is the adapter that makes "point
 * it at the model on my own machine" a single flag rather than a code change. Nothing here
 * names a provider or a model: both arrive as configuration, the same way the phone's
 * endpoint does.
 *
 * `HttpURLConnection` rather than a client library, matching the rest of the project. The
 * request is one POST.
 */
public class OpenAiCompatibleClient(
    /** For example `http://localhost:4000/v1`. The `/chat/completions` is appended. */
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double? = null,
    private val timeoutMs: Int = 180_000,
) : ModelClient {

    private val endpoint: String = baseUrl.trimEnd('/').let {
        if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override val describe: String get() = "$model via $endpoint"

    override fun complete(turns: List<Turn>, tools: List<ToolSpec>): ModelReply {
        val body = buildJsonObject {
            put("model", model)
            temperature?.let { put("temperature", it) }
            put("messages", encodeMessages(turns))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }.toString()

        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // The error body is where a gateway says what it actually objected to --
                // an unknown model, a missing key, a tool schema it would not accept.
                // Swallowing it would turn every one of those into the same dead end.
                val detail = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                throw ModelException("The model endpoint answered $code: ${detail.take(600)}")
            }
            val response = connection.inputStream.readBytes().toString(Charsets.UTF_8)
            return parse(response)
        } catch (e: ModelException) {
            throw e
        } catch (e: Exception) {
            throw ModelException("Could not reach the model endpoint at $endpoint: ${e.message}")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parse(response: String): ModelReply {
        val message = runCatching {
            json.parseToJsonElement(response).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject
        }.getOrElse { throw ModelException("Unreadable reply from the model endpoint: ${response.take(600)}") }

        val text = message["content"]?.jsonPrimitive?.contentOrNull
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
            val call = element.jsonObject
            val function = call["function"]?.jsonObject ?: return@mapNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                // Arguments arrive as a JSON *string*, and a model can produce an empty or
                // malformed one. Normalising to "{}" here means a bad argument becomes an
                // action the device rejects with a structured failure the model can read,
                // rather than an exception that ends the run.
                argumentsJson = function["arguments"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: "{}",
            )
        }
        return ModelReply(text?.takeIf { it.isNotBlank() }, calls)
    }

    private fun encodeTools(tools: List<ToolSpec>) = buildJsonArray {
        for (tool in tools) {
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        // MCP has no risk field and neither does this shape, so the risk
                        // rides in the description. It is advice: the phone enforces.
                        put("description", tool.description + " (risk: ${tool.maxRisk})")
                        put("parameters", tool.parameterSchema)
                    }
                },
            )
        }
    }

    private fun encodeMessages(turns: List<Turn>) = buildJsonArray {
        for (turn in turns) {
            add(
                when (turn) {
                    is Turn.System -> buildJsonObject {
                        put("role", "system")
                        put("content", turn.text)
                    }
                    is Turn.User -> buildJsonObject {
                        put("role", "user")
                        put("content", turn.text)
                    }
                    is Turn.Assistant -> buildJsonObject {
                        put("role", "assistant")
                        put("content", turn.text)
                        if (turn.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                for (call in turn.toolCalls) {
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.argumentsJson)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Every tool_call the assistant made must come back with a matching
                    // tool message or the next request is rejected outright, so the loop
                    // answers even the calls it refused to run.
                    is Turn.ToolResult -> buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", turn.callId)
                        put("name", turn.name)
                        put("content", turn.content)
                    }
                },
            )
        }
    }
}

/** The model endpoint could not be reached, or said something unusable. */
public class ModelException(message: String) : RuntimeException(message)

/** Merges the model's arguments object with the tool name the SDK expects as a discriminator. */
internal fun actionPayload(name: String, argumentsJson: String, json: Json): String {
    val arguments = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
    return buildJsonObject {
        put("type", name)
        arguments.forEach { (key, value) -> put(key, value) }
    }.toString()
}
