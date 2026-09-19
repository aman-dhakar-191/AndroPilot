package com.andropilot.core.util

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration used everywhere the SDK serializes.
 *
 * Shared rather than duplicated so that what a recorder writes to disk is byte-identical to
 * what a remote agent receives over the wire -- a recorded trace is then a valid input to
 * anything that consumes live results, which is the whole point of recording one.
 */
public object AndroPilotJson {
    public val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
}
