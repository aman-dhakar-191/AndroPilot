package com.andropilot.host.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * What the configured endpoint says it can answer to.
 *
 * The host cannot know this by itself and must not guess. A gateway routes on a string,
 * and that string is not always a model: it may name a group the gateway resolves for
 * itself, and the spelling of that name is a thing somebody typed into a dashboard. Getting
 * it wrong produces one 400 per attempt with no list of what would have worked, which is
 * exactly the loop this removes -- the endpoint already publishes the answer at `/models`.
 *
 * Failure is a value, not an exception: an endpoint that does not implement `/models` is
 * common and is not a reason for the page to break. The caller falls back to typing a name.
 */
public class ModelCatalog(baseUrl: String, private val apiKey: String) {

    private val endpoint: String = baseUrl.trimEnd('/').removeSuffix("/chat/completions") + "/models"

    /** Ids the endpoint advertises, sorted, or an empty list if it would not say. */
    public fun list(): List<String> = runCatching {
        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching emptyList()
            parse(connection.inputStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pulls ids out of an OpenAI-shaped `/models` body.
         *
         * Tolerant of the shape because this is the one call whose failure must not matter:
         * a body that is a bare array, or entries that carry `id` under another spelling,
         * still yield names rather than nothing.
         */
        public fun parse(body: String): List<String> = runCatching {
            val root = json.parseToJsonElement(body)
            val entries = when {
                root is kotlinx.serialization.json.JsonArray -> root
                else -> root.jsonObject["data"]?.jsonArray ?: return@runCatching emptyList()
            }
            entries.mapNotNull { entry ->
                when (entry) {
                    is kotlinx.serialization.json.JsonObject ->
                        (entry["id"] ?: entry["name"] ?: entry["model"])?.jsonPrimitive?.contentOrNull
                    is kotlinx.serialization.json.JsonPrimitive -> entry.contentOrNull
                    else -> null
                }
            }.filter { it.isNotBlank() }.distinct().sorted()
        }.getOrDefault(emptyList())
    }
}
