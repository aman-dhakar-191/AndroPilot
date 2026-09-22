package com.andropilot.host.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/** One thing the endpoint will answer to. */
public data class ModelOption(
    val id: String,
    /**
     * "combo" or "model", from the endpoint's own `owned_by`.
     *
     * A gateway's groups arrive in the same listing as its models and are only
     * distinguishable by this, so the page can say which is which.
     */
    val group: String,
    /**
     * Whether the endpoint claims this can call tools.
     *
     * The loop is nothing but tool calls, so a model without them cannot drive a phone --
     * it will answer in prose until the step ceiling. Assumed true when the endpoint does
     * not say, because refusing on silence would rule out endpoints that publish no
     * capabilities at all.
     */
    val toolCalling: Boolean = true,
)

/**
 * What came back, and whether the endpoint would say at all.
 *
 * `listed` is the distinction that decides whether the page may call a typed name wrong.
 * An endpoint that refused the request, or implements no catalogue, has not told us the
 * name is bad -- and a picker that treated silence as an empty set would report a working
 * configuration as invalid.
 */
public data class Catalog(
    val options: List<ModelOption>,
    val listed: Boolean,
    /** Why the listing is empty, when it is, in words a person can act on. */
    val problem: String? = null,
)

/**
 * What the configured endpoint says it can answer to.
 *
 * The host cannot know this by itself and must not guess: a gateway routes on a string
 * somebody typed into a dashboard, and getting it wrong costs one failed run that reports
 * only that the name was unknown.
 *
 * **The listing is per-credential.** A gateway shows its own groups only to the key that
 * owns them -- unauthenticated, the same endpoint returns a long list of public models and
 * silently omits every combo. So a catalogue that looks complete can still be missing the
 * one name that matters, and the key this is built with is the one the loop uses.
 */
public class ModelCatalog(baseUrl: String, private val apiKey: String) {

    private val endpoint: String =
        baseUrl.trimEnd('/').removeSuffix("/chat/completions").trimEnd('/') + "/models"

    public fun list(): Catalog {
        val connection = runCatching {
            (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 15_000
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }.getOrElse { return Catalog(emptyList(), listed = false, problem = "$endpoint is not a URL this can fetch") }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // Named rather than swallowed: a 401 here means the catalogue is the public
                // one, which is exactly the case where a present combo looks absent.
                Catalog(emptyList(), listed = false, problem = "$endpoint answered $code")
            } else {
                val body = connection.inputStream.readBytes().toString(Charsets.UTF_8)
                Catalog(parse(body), listed = true)
            }
        } catch (e: Exception) {
            Catalog(emptyList(), listed = false, problem = e.message ?: "could not read $endpoint")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pulls entries out of an OpenAI-shaped `/models` body.
         *
         * Tolerant of the envelope because this call's failure must not matter: a bare
         * array, or entries naming themselves differently, still yield something.
         */
        public fun parse(body: String?): List<ModelOption> = runCatching {
            if (body.isNullOrBlank()) return emptyList()
            val root = json.parseToJsonElement(body)
            val entries: JsonArray = when {
                root is JsonArray -> root
                root is JsonObject -> (root["data"] ?: root["models"]) as? JsonArray ?: return emptyList()
                else -> return emptyList()
            }
            entries.mapNotNull { entry ->
                when (entry) {
                    is JsonObject -> {
                        val id = (entry["id"] ?: entry["name"] ?: entry["model"])
                            ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val owner = (entry["owned_by"] ?: entry["group"])?.jsonPrimitive?.contentOrNull
                        val tools = entry["tools"]?.jsonPrimitive?.booleanOrNull
                            ?: (entry["capabilities"] as? JsonObject)
                                ?.get("tool_calling")?.jsonPrimitive?.booleanOrNull
                            ?: true
                        ModelOption(id, if (owner == "combo") "combo" else "model", tools)
                    }
                    is JsonPrimitive -> entry.contentOrNull?.let { ModelOption(it, "model") }
                    else -> null
                }
            }.filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                // Combos first: they are what somebody configured deliberately, and there
                // are a handful of them against hundreds of models.
                .sortedWith(compareBy({ it.group != "combo" }, { it.id }))
        }.getOrDefault(emptyList())
    }
}
