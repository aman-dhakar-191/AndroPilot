package com.andropilot.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/**
 * Finds the package a result describes, so the matching app notes can ride along.
 *
 * Parsed generically rather than through the SDK's model: the host deliberately does not
 * depend on core, and a result it cannot fully parse must still be forwarded intact.
 *
 * One definition, used by both the MCP path and the loop. They ask the same question of the
 * same documents, and two answers that drifted apart would mean an app's notes reached a
 * client but not the model actually driving the phone.
 */
internal fun packageOf(payload: String): String? = runCatching {
    fun search(element: JsonElement): String? = when (element) {
        is JsonObject -> element["package_name"]?.jsonPrimitive?.contentOrNull
            ?: element.values.firstNotNullOfOrNull(::search)
        is JsonArray -> element.firstNotNullOfOrNull(::search)
        else -> null
    }
    search(json.parseToJsonElement(payload))
}.getOrNull()
