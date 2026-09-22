package com.andropilot.host.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/** One thing the endpoint will answer to, and where the host learned about it. */
public data class ModelOption(
    val id: String,
    /** "model" or "combo". The page groups by this; the host sends only `id`. */
    val group: String,
)

/**
 * What the configured endpoint says it can answer to.
 *
 * The host cannot know this by itself and must not guess. A gateway routes on a string,
 * and that string is not always a model: it may name a group the gateway resolves for
 * itself, and the spelling of that name is a thing somebody typed into a dashboard.
 *
 * **A gateway's own groups are not in `/v1/models`.** That endpoint is the OpenAI-shaped
 * catalogue and lists models; a combo is the gateway's own concept and lives behind its own
 * API. Asking only the standard endpoint produces a long list that confidently omits the
 * one name somebody configured, which is worse than no list at all -- so both are read and
 * the page says which is which.
 *
 * Failure is a value, not an exception: an endpoint that implements neither is common and
 * is not a reason for the page to break. The caller falls back to typing a name.
 */
public class ModelCatalog(baseUrl: String, private val apiKey: String) {

    private val base: String = baseUrl.trimEnd('/').removeSuffix("/chat/completions")

    /** The server root, since a gateway's own API sits beside `/v1` rather than under it. */
    private val root: String = base.removeSuffix("/v1").removeSuffix("/openai").trimEnd('/')

    /** Everything the endpoint offers, models first, or empty if it would not say. */
    public fun list(): List<ModelOption> {
        val models = parseModels(get("$base/models")).map { ModelOption(it, "model") }
        // Both spellings, because either resolves and a bare name can be shadowed by a
        // model id of the same name -- the prefixed one never is.
        val combos = parseCombos(get("$root/api/combos")).flatMap {
            listOf(ModelOption(it, "combo"), ModelOption("combo/$it", "combo"))
        }
        return (combos + models).distinctBy { it.id }
    }

    private fun get(url: String): String? = runCatching {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 15_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Ids out of an OpenAI-shaped `/models` body. */
        public fun parseModels(body: String?): List<String> =
            names(body, arrayOf("data")) { it["id"] ?: it["name"] ?: it["model"] }

        /**
         * Names out of a gateway's combo listing.
         *
         * Shape-tolerant on purpose: this is an API outside any standard, so the envelope
         * key and the field holding the name are things to discover rather than assume.
         */
        public fun parseCombos(body: String?): List<String> =
            names(body, arrayOf("combos", "data", "items")) { it["name"] ?: it["id"] }

        private fun names(
            body: String?,
            envelopes: Array<String>,
            pick: (JsonObject) -> kotlinx.serialization.json.JsonElement?,
        ): List<String> = runCatching {
            if (body.isNullOrBlank()) return emptyList()
            val root = json.parseToJsonElement(body)
            val entries: JsonArray = when {
                root is JsonArray -> root
                root is JsonObject -> envelopes.firstNotNullOfOrNull { root[it] as? JsonArray }
                    ?: return emptyList()
                else -> return emptyList()
            }
            entries.mapNotNull { entry ->
                when (entry) {
                    is JsonObject -> pick(entry)?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> entry.contentOrNull
                    else -> null
                }
            }.filter { it.isNotBlank() }.distinct().sorted()
        }.getOrDefault(emptyList())
    }
}
