package com.andropilot.core.session

import com.andropilot.core.action.ActionData
import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Describes one capability in a way any AI runtime can consume.
 *
 * Intentionally *not* an OpenAI function definition, an Anthropic tool block, or a Gemini
 * declaration. It is the union of what those formats need -- a name, a description, and a
 * JSON Schema for the arguments -- so an integrator writes a ten-line adapter for whichever
 * runtime they use and the SDK stays free of provider coupling.
 */
public data class ToolDescriptor(
    val name: String,
    val description: String,
    /** JSON Schema (draft 2020-12 subset) for the action's arguments. */
    val parameterSchema: JsonObject,
    /** Highest risk this tool can carry, so a runtime can gate it up front. */
    val maxRisk: com.andropilot.core.safety.RiskLevel,
)

/**
 * Bridges between the typed action model and neutral JSON.
 *
 * The wire format is the action's own `kotlinx.serialization` polymorphic encoding: a
 * `"type"` discriminator plus the action's fields. That keeps exactly one schema in the
 * codebase instead of a hand-written parallel mapping that drifts.
 */
public object ToolCodec {

    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }

    private val prettyJson: Json = Json(json) { prettyPrint = true }

    public fun encodeAction(action: AgentAction): String = json.encodeToString(AgentAction.serializer(), action)

    public fun decodeAction(payload: String): AgentAction =
        json.decodeFromString(AgentAction.serializer(), payload)

    public fun encodeResult(result: ActionResult, pretty: Boolean = false): String =
        (if (pretty) prettyJson else json).encodeToString(ActionResult.serializer(), result)

    public fun decodeResult(payload: String): ActionResult =
        json.decodeFromString(ActionResult.serializer(), payload)

    /**
     * Executes a JSON action and returns a JSON result.
     *
     * This single function is the entire remote-agent transport contract: put any byte pipe
     * (HTTP, a socket, ADB, a local IPC binder) in front of it and a remote agent can drive
     * the device. A malformed payload comes back as a structured failure, never an
     * exception, so a transport never has to translate error shapes.
     */
    public suspend fun executeJson(session: AndroPilotSession, payload: String): String {
        val action = try {
            decodeAction(payload)
        } catch (e: Exception) {
            return encodeResult(
                ActionResult.Failure(
                    action = AgentAction.Observe(),
                    durationMs = 0,
                    reason = com.andropilot.core.action.FailureReason.INVALID_REQUEST,
                    message = "The action payload could not be parsed: ${e.message}",
                    recommendation = "Send one of: ${ACTION_NAMES.joinToString()}.",
                ),
            )
        }
        return encodeResult(session.execute(action))
    }

    /**
     * Produces a compact, model-friendly rendering of a result.
     *
     * Full JSON results carry an entire snapshot and are far too large to feed back into a
     * model on every turn. This is what an agent loop should actually put in its context.
     */
    public fun summarizeForModel(result: ActionResult, maxElements: Int = 80): String = buildString {
        when (result) {
            is ActionResult.Success -> {
                append("OK ").append(result.action.name)
                result.target?.let { append(" -> ").append(it.describe()) }
                result.diff?.let { append(" (").append(it.summarize()).append(')') }
                result.warnings.forEach { append("\nwarning: ").append(it) }
                when (val data = result.data) {
                    is ActionData.BooleanValue -> append("\nresult: ").append(data.value)
                    is ActionData.Element -> append("\nfound: ").append(data.element.describe())
                    is ActionData.ScreenshotData ->
                        append("\nscreenshot: ").append(data.width).append('x').append(data.height)
                            .append(" png, base64 omitted from summary")
                    is ActionData.SnapshotData ->
                        append('\n').append(data.snapshot.toCompactText(maxElements))
                    null -> result.snapshot?.let { append('\n').append(it.toCompactText(maxElements)) }
                }
            }
            is ActionResult.Failure -> {
                append("FAILED ").append(result.action.name)
                append(" [").append(result.reason.name.lowercase()).append("] ")
                append(result.message)
                result.recommendation?.let { append("\nhint: ").append(it) }
                if (result.candidates.isNotEmpty()) {
                    append("\ncandidates:")
                    result.candidates.take(5).forEach {
                        append("\n  - ").append(it.element.describe())
                    }
                }
                result.pendingConfirmation?.let {
                    append("\nconfirmation required: ").append(it.description)
                    append(" (id=").append(it.id).append(')')
                }
            }
        }
    }

    /** Every action name the SDK accepts. */
    public val ACTION_NAMES: List<String> = listOf(
        "observe", "screenshot", "find_element", "element_exists",
        "click", "click_point", "long_press", "type_text", "clear_text",
        "scroll", "scroll_until", "swipe", "press_key",
        "launch_app", "open_intent", "wait_for", "sleep", "verify",
    )

    /**
     * Tool definitions for an agent runtime, in provider-neutral form.
     *
     * Schemas are hand-written rather than reflected because a *description* is what makes
     * a model use a tool correctly, and generated schemas have none.
     */
    public fun toolDescriptors(): List<ToolDescriptor> = listOf(
        tool(
            "observe",
            "Capture the current screen as a structured list of UI elements. Call this " +
                "before acting, and whenever you are unsure what is on screen.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) {
            put("include_visual", boolProp("Also run visual analysis. Slower; use when the element list looks empty or unlabelled."))
            put("wait_for_settle", boolProp("Wait for animations to finish first. Defaults to true."))
        },
        tool(
            "find_element",
            "Locate a single element by a semantic selector without interacting with it. " +
                "Returns the element, its score, and any alternatives.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) { put("selector", selectorProp()) },
        tool(
            "element_exists",
            "Check whether an element matching the selector is currently on screen.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) { put("selector", selectorProp()) },
        tool(
            "click",
            "Tap the element matching the selector. Prefers the app's own accessibility " +
                "click and falls back to a synthesized tap. Reports whether the UI changed.",
            com.andropilot.core.safety.RiskLevel.SENSITIVE,
        ) {
            put("selector", selectorProp())
            put("allow_gesture_fallback", boolProp("Allow a coordinate tap if the semantic click fails. Defaults to true."))
        },
        tool(
            "click_point",
            "Tap absolute screen coordinates. Use ONLY when no selector can identify the " +
                "target, for example inside a canvas or a game.",
            com.andropilot.core.safety.RiskLevel.SENSITIVE,
        ) { put("point", pointProp()) },
        tool(
            "long_press",
            "Press and hold the element matching the selector.",
            com.andropilot.core.safety.RiskLevel.SENSITIVE,
        ) {
            put("selector", selectorProp())
            put("duration_ms", intProp("Hold duration in milliseconds. Defaults to 600."))
        },
        tool(
            "type_text",
            "Enter text into a field. Omit the selector to type into the focused field. " +
                "Set `sensitive` for passwords and one-time codes so the value is never logged.",
            com.andropilot.core.safety.RiskLevel.SENSITIVE,
        ) {
            put("selector", selectorProp())
            put("text", stringProp("The text to enter."))
            put("replace", boolProp("Replace the field contents rather than appending. Defaults to true."))
            put("press_ime_action", boolProp("Press the keyboard's action key (search/next/done) afterwards."))
            put("sensitive", boolProp("Mark as a credential or one-time code."))
        },
        tool(
            "clear_text",
            "Empty a text field. Prefer this over typing an empty string when you only " +
                "want to discard the current contents.",
            com.andropilot.core.safety.RiskLevel.MUTATING,
        ) { put("selector", selectorProp()) },
        tool(
            "scroll",
            "Scroll a container. Omit the selector to scroll the largest scrollable area.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("direction", enumProp("Which way to reveal content.", listOf("down", "up", "left", "right")))
            put("selector", selectorProp())
            put("amount", numberProp("Fraction of the container to travel, 0.1-0.9. Defaults to 0.6."))
            put("steps", intProp("Number of consecutive scrolls. Defaults to 1."))
        },
        tool(
            "scroll_until",
            "Scroll repeatedly until a condition holds or the content stops moving. The " +
                "reliable way to reach an item below the fold.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("until", conditionProp())
            put("direction", enumProp("Which way to scroll.", listOf("down", "up", "left", "right")))
            put("selector", selectorProp())
            put("max_steps", intProp("Give up after this many scrolls. Defaults to 12."))
        },
        tool(
            "swipe",
            "Swipe between two absolute points. Use for gestures a scroll cannot express, " +
                "such as dismissing a card.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("start", pointProp())
            put("end", pointProp())
            put("duration_ms", intProp("Gesture duration in milliseconds. Defaults to 300."))
        },
        tool(
            "press_key",
            "Press a system key. `back` is the reliable way to dismiss a dialog or leave a " +
                "screen; prefer it over hunting for an on-screen close button.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("key", enumProp("Which key.", listOf("back", "home", "recents", "notifications")))
        },
        tool(
            "launch_app",
            "Open an application by package name and wait for it to reach the foreground.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("package_name", stringProp("Android package name, e.g. com.android.settings."))
            put("wait_for_foreground", boolProp("Wait until the app is actually in front. Defaults to true."))
        },
        tool(
            "open_intent",
            "Fire an Android intent. Restricted to the host's allow-list.",
            com.andropilot.core.safety.RiskLevel.NAVIGATION,
        ) {
            put("action", stringProp("Intent action, e.g. android.intent.action.VIEW."))
            put("uri", stringProp("Optional data URI."))
            put("package_name", stringProp("Optional target package."))
        },
        tool(
            "screenshot",
            "Capture the screen as a PNG image, base64 encoded, for visual inspection.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) {
            put("max_dimension", intProp("Longest edge in pixels; the image is downscaled to fit."))
        },
        tool(
            "wait_for",
            "Block until a UI condition holds or the timeout expires.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) {
            put("condition", conditionProp())
            put("timeout_ms", intProp("Give up after this many milliseconds. Defaults to 5000."))
        },
        tool(
            "verify",
            "Assert that the expected UI state has been reached. Use this after an action " +
                "to close the loop instead of assuming it worked.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) {
            put("condition", conditionProp())
            put("timeout_ms", intProp("Grace period in milliseconds. Defaults to 2000."))
        },
        tool(
            "sleep",
            "Wait unconditionally. Prefer wait_for whenever a condition can express the wait.",
            com.andropilot.core.safety.RiskLevel.READ_ONLY,
        ) {
            put("duration_ms", intProp("Milliseconds to wait."))
        },
    )

    private fun tool(
        name: String,
        description: String,
        maxRisk: com.andropilot.core.safety.RiskLevel,
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ToolDescriptor = ToolDescriptor(
        name = name,
        description = description,
        maxRisk = maxRisk,
        parameterSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject(properties))
            put("additionalProperties", false)
        },
    )

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }

    private fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean"); put("description", description)
    }

    private fun intProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }

    private fun numberProp(description: String) = buildJsonObject {
        put("type", "number"); put("description", description)
    }

    private fun enumProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", json.encodeToJsonElement(values))
    }

    private fun pointProp() = buildJsonObject {
        put("type", "object")
        put("description", "A screen point in device pixels, origin top-left.")
        put(
            "properties",
            buildJsonObject {
                put("x", intProp("Horizontal pixel coordinate."))
                put("y", intProp("Vertical pixel coordinate."))
            },
        )
        put("required", json.encodeToJsonElement(listOf("x", "y")))
    }

    private fun selectorProp() = buildJsonObject {
        put("type", "object")
        put(
            "description",
            "How to identify the element. Combine fields to narrow it down; they are ANDed. " +
                "`text` is fuzzy-matched against the label, description and hint.",
        )
        put(
            "properties",
            buildJsonObject {
                put("text", stringProp("Visible label, content description or hint. Fuzzy matched."))
                put("exact_text", stringProp("Exact visible text. Use when fuzzy matching is too loose."))
                put("resource_id", stringProp("Android view id, with or without the package prefix."))
                put("role", enumProp("Element kind.", ROLE_NAMES))
                put("region", enumProp("Coarse screen region, to disambiguate duplicates.", listOf("top", "bottom", "left", "right", "center")))
                put("clickable", boolProp("Require the element to be clickable."))
                put("editable", boolProp("Require the element to be a text field."))
                put("scrollable", boolProp("Require the element to be scrollable."))
                put("checked", boolProp("Require a specific checked state."))
                put("enabled", boolProp("Require a specific enabled state."))
                put("index", intProp("0-based pick among equally good matches, in reading order."))
                put("within", buildJsonObject { put("type", "object"); put("description", "Restrict to descendants of this element.") })
                put("near", buildJsonObject { put("type", "object"); put("description", "Prefer the candidate closest to this element.") })
                put("min_score", numberProp("Match threshold 0-1. Defaults to 0.55. Raise it to be stricter."))
            },
        )
    }

    private fun conditionProp() = buildJsonObject {
        put("type", "object")
        put(
            "description",
            "A UI condition. Set `type` to one of: element_present, element_absent, " +
                "package_in_foreground, text_visible, ui_settled, all_of, any_of.",
        )
        put(
            "properties",
            buildJsonObject {
                put("type", enumProp("Condition kind.", CONDITION_NAMES))
                put("selector", buildJsonObject { put("type", "object"); put("description", "For element_present / element_absent.") })
                put("text", stringProp("For text_visible."))
                put("package_name", stringProp("For package_in_foreground."))
            },
        )
        put("required", json.encodeToJsonElement(listOf("type")))
    }

    private val ROLE_NAMES = com.andropilot.core.model.ElementRole.entries.map { it.name.lowercase() }

    private val CONDITION_NAMES = listOf(
        "element_present", "element_absent", "package_in_foreground",
        "text_visible", "ui_settled", "all_of", "any_of",
    )
}
