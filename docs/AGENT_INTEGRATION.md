# Wiring AndroPilot up to an AI runtime

The SDK deliberately hard-codes no tool-calling format. Every capability is one
`AgentAction`, so a runtime adapter is small, and swapping providers does not touch the SDK.

## The whole transport contract

```kotlin
suspend fun handle(actionJson: String): String =
    ToolCodec.executeJson(session, actionJson)
```

That is it. Any byte pipe in front of that function — HTTP, a socket, a local binder, ADB —
lets a remote agent drive the device. Malformed payloads come back as structured failures,
never exceptions, so a transport never has to translate error shapes.

The wire format is the action's own discriminated encoding:

```json
{"type": "click", "selector": {"text": "Sign in", "role": "button"}}
{"type": "type_text", "selector": {"resource_id": "email"}, "text": "sam@example.com"}
{"type": "scroll_until", "until": {"type": "text_visible", "text": "Total"}, "direction": "down"}
```

## Exposing the actions as tools

`ToolCodec.toolDescriptors()` returns provider-neutral descriptors — a name, a description
written for a model to read, and a JSON Schema. Project them into whatever your runtime wants:

```kotlin
// Anthropic-shaped
val tools = ToolCodec.toolDescriptors().map { d ->
    mapOf("name" to d.name, "description" to d.description, "input_schema" to d.parameterSchema)
}

// OpenAI-shaped
val tools = ToolCodec.toolDescriptors().map { d ->
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to d.name,
            "description" to d.description,
            "parameters" to d.parameterSchema,
        ),
    )
}
```

Then route a tool call back through `executeJson`, merging the tool name into the payload as
`"type"`.

`ToolDescriptor.maxRisk` lets a runtime gate destructive tools up front — for example, only
offering `click` and `type_text` once a human is available to answer confirmations.

## Feeding results back into context

Do **not** put a full `ActionResult` JSON in a model's context: it carries an entire snapshot
and will dominate the window. Use the compact rendering:

```kotlin
val summary = ToolCodec.summarizeForModel(result, maxElements = 80)
```

```
OK click -> button "Sign in" [60,920][1020,1060] (screen content changed; new: Hello, Sam)

FAILED click [ambiguous_target] 2 elements matched Selector(text~"Choose") with similar confidence.
hint: Disambiguate with `index`, `region`, `within`, or `near`. Candidates: button "Choose" …
candidates:
  - button "Choose" [60,420][520,540]
  - button "Choose" [560,420][1020,540]
```

Failure summaries carry the machine-readable reason, a recommendation, and the candidates —
which is usually enough for a model to correct itself on the next turn without another
`observe`.

## A minimal agent loop

```kotlin
var context = ToolCodec.summarizeForModel(session.observe())
repeat(maxSteps) {
    val toolCall = model.nextAction(goal, context)          // your runtime
    val result = session.execute(ToolCodec.decodeAction(toolCall))
    context = ToolCodec.summarizeForModel(result)

    if (result is ActionResult.Failure &&
        result.reason == FailureReason.CONFIRMATION_REQUIRED
    ) {
        // Never let the model approve its own sensitive action.
        val approved = askTheHuman(result.pendingConfirmation!!)
        session.resolveConfirmation(result.pendingConfirmation!!.id, outcome(approved))
    }
}
```

Two things worth building into any loop:

- **Branch on `FailureReason`, not on the message.** `isTransient` tells you whether retrying
  the same action unchanged could plausibly work; anything else means change the plan.
- **Trust `diff.changed` over the model's assumption.** The most common failure mode in agent
  automation is a model planning three steps against a screen that never changed.

## Local vs remote agents

Both are supported by the same surface, because the session is just an object:

- **Local** (a model on or near the device) — call `session.execute(...)` directly. No
  serialization cost; the typed API is the fast path.
- **Remote** — put `executeJson` behind a transport. The results are already fully
  serializable, including snapshots, so a remote agent has exactly the information a local
  one does.

If you go remote, note that snapshots and screenshots contain whatever is on the user's
screen. Decide deliberately what leaves the device — `SessionConfig.allowTextInLogs` governs
logs, but the action results themselves are yours to filter.

## What the SDK will not do for you

Planning, memory, retry strategy above the single-action level, and deciding *what* to
automate are the agent's job. The SDK's contract stops at: this action, executed as reliably
as Android permits, with an honest report of what happened.
