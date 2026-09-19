# Integrating AndroPilot

You do not need to understand Android accessibility internals to use this SDK. You need four
things: a dependency, a permission, one initialization call, and a session.

## 1. Add the dependency

Release builds publish `andropilot-core-<version>.jar` and `andropilot-android-<version>.aar`
as GitHub release assets, and `publishToMavenLocal` is wired up for local development.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); mavenLocal() }
}

// app/build.gradle.kts
dependencies {
    implementation("com.andropilot:andropilot-android:0.1.0")
    // andropilot-core comes transitively; depend on it directly in pure-JVM test modules.
}
```

Or vendor the modules directly while the project is young:

```kotlin
includeBuild("../AndroPilot")
```

`minSdk` is 26. Screenshots additionally require API 30.

## 2. Declare and request the permission

The library manifest already declares the accessibility service, so nothing is needed in your
manifest. What you *do* need is to send the user to grant it — the SDK cannot and must not
grant itself this permission.

```kotlin
if (!AndroPilot.isServiceEnabled(context)) {
    // Explain, in your own UI, what the agent will be able to do. Then:
    AndroPilot.openAccessibilitySettings(context)
}
```

Observe `AndroPilot.serviceConnected` (a `StateFlow<Boolean>`) to react when it is granted or
revoked.

> **One thing to remove if you can.** The library declares `QUERY_ALL_PACKAGES` so
> `launch_app` can resolve an arbitrary package on API 30+. If you only drive a known set of
> apps, strip it and declare a `<queries>` block instead — it is narrower and more
> privacy-respecting, and `launch_app` still reports `APP_UNAVAILABLE` cleanly for anything
> outside it.
>
> ```xml
> <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
>     tools:node="remove" />
> <queries>
>     <package android:name="com.example.target" />
> </queries>
> ```

## 3. Initialize once

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroPilot.initialize(this)
    }
}
```

The defaults are production-safe: sensitive actions require confirmation, and no screen text
is written to logs.

## 4. Drive the device

```kotlin
val session = AndroPilot.session()

when (val result = session.click(Selector.text("Sign in"))) {
    is ActionResult.Success -> {
        // result.target       -- the element that was actually tapped
        // result.matchReason  -- how it was chosen, e.g. `resource id exact`
        // result.diff         -- what changed on screen
        // result.interactionMode -- SEMANTIC, GESTURE, VISUAL or SYSTEM
    }
    is ActionResult.Failure -> when (result.reason) {
        FailureReason.ELEMENT_NOT_FOUND    -> // result.candidates holds the near misses
        FailureReason.AMBIGUOUS_TARGET     -> // disambiguate with index/region/within/near
        FailureReason.CONFIRMATION_REQUIRED -> // result.pendingConfirmation, ask the user
        FailureReason.PERMISSION_REQUIRED  -> // send them to accessibility settings
        else                               -> // result.message + result.recommendation
    }
}
```

Actions never throw for expected conditions. Exceptions are reserved for programming errors
and cancellation.

## Selectors

```kotlin
Selector.text("Sign in")                         // fuzzy: matches "SIGN IN", "Sign In"
Selector.id("submit")                            // with or without the package prefix
Selector(text = "Delete", role = ElementRole.BUTTON, enabled = true)
Selector(text = "Choose", region = ScreenRegion.RIGHT)          // disambiguate by position
Selector(text = "Choose", near = Selector.text("Pro"))          // disambiguate by proximity
Selector(text = "Cancel", within = Selector.role(ElementRole.DIALOG))
Selector(text = "Item", index = 2)                              // nth equally good match
Selector(text = "Submit", minScore = 0.9)                       // stricter matching
```

Boolean fields (`clickable`, `editable`, `enabled`, `checked`, …) are hard filters. Text is
fuzzy-scored. If two candidates tie, you get `AMBIGUOUS_TARGET` with both — the SDK will not
guess which "Delete" you meant.

## Waiting and verifying

Prefer conditions over sleeps. A fixed delay is either flaky or slow and never adapts to the
device.

```kotlin
session.waitFor(UiCondition.ElementPresent(Selector.text("Order complete")), timeoutMs = 10_000)
session.execute(AgentAction.Verify(UiCondition.TextVisible("Welcome")))

// Reach an item below the fold:
session.execute(
    AgentAction.ScrollUntil(
        until = UiCondition.ElementPresent(Selector.text("Delete account")),
        direction = Direction.DOWN,
    ),
)
```

`ScrollUntil` stops early when the content stops moving, so an unreachable target fails in a
second or two rather than burning the full step budget.

## Handling confirmations

```kotlin
val result = session.click(Selector.text("Send"))
if (result is ActionResult.Failure &&
    result.reason == FailureReason.CONFIRMATION_REQUIRED
) {
    val confirmation = result.pendingConfirmation!!
    // confirmation.description is already redacted and safe to show a human.
    val approved = showDialog(confirmation.description, confirmation.reasons)
    session.resolveConfirmation(
        confirmation.id,
        if (approved) ConfirmationOutcome.APPROVED else ConfirmationOutcome.REJECTED,
    )
    if (approved) session.click(Selector.text("Send"))  // repeat; the approval is single-use
}
```

## Configuring policy

```kotlin
AndroPilot.initialize(
    this,
    SessionConfig(
        // Confirm anything with a side effect, not just destructive actions:
        policy = DefaultSafetyPolicy(confirmAtOrAbove = RiskLevel.MUTATING),

        // Or restrict the agent to specific apps and intents entirely:
        // policy = DefaultSafetyPolicy(
        //     allowedPackages = setOf("com.example.target"),
        //     allowedIntentActions = emptySet(),
        // ),

        settleTimeoutMs = 4_000,          // animation-heavy apps
        treatNoEffectAsFailure = true,    // surface silent no-ops (recommended)
        visionProvider = myOcrProvider,   // optional; see below
        logger = AgentLogger.console(),
        allowTextInLogs = false,          // keep this false outside local debugging
    ),
)
```

For full control, implement `SafetyPolicy` yourself — it is a single method that receives the
action, the current snapshot and the resolved target.

## Plugging in vision

Only needed for apps with a poor or absent accessibility tree.

```kotlin
val provider = VisionProvider { screenshot, semanticHints ->
    myOcrEngine.recognize(screenshot.pngBytes).map { word ->
        VisualElement(
            bounds = Bounds(word.left, word.top, word.right, word.bottom),
            text = word.text,
            confidence = word.confidence,
        )
    }
}
```

The SDK skips the provider entirely when the semantic tree is already informative, so you do
not pay for a screenshot and an analysis pass on every observation.

## Testing your own agent logic

`FakeUiDriver` and the `FakeScreens` catalogue ship in the main source set precisely so you
can do this without an emulator:

```kotlin
@Test
fun `the planner signs in`() = runTest {
    val driver = FakeUiDriver(FakeScreens.login())
    val session = DefaultAndroPilotSession(driver, SessionConfig.debug())

    myPlanner.run(session)

    assertEquals("sam@example.com", driver.screen.elements.first { it.id == "user" }.text)
}
```

Build your own screens with the `Ui` builders (`Ui.button`, `Ui.field`, `Ui.list`, …), or
start from `FakeScreens.login()`, `.settingsList()`, `.duplicateButtons()`,
`.confirmDialog()`, `.opaqueCanvas()`.

## Debugging

```kotlin
session.trace().forEach { println(it.format()) }
// #3 FAIL click (412ms) in com.example.shop element_not_found: Nothing on screen matched …

println(session.lastSnapshot()?.toCompactText())   // exactly what an agent would see
```

Or install the demo app (`:demo`), which surfaces all of this on-device: the live element
list, the compact rendering an agent receives, the action trace, and the confirmation queue.

## Gotchas

- **`PERMISSION_REQUIRED` on every action** — the accessibility service is not enabled, or the
  system disabled it after a crash. Check `AndroPilot.serviceConnected`.
- **`NO_PERCEPTION`** — the app exposes no accessibility tree, or the screen is `FLAG_SECURE`.
  Register a `VisionProvider`, or accept that this screen is not automatable.
- **`NO_EFFECT` on a tap you believe worked** — the effect may be off-screen, or the element
  may be decorative. Check the target in `result.action`; set `treatNoEffectAsFailure = false`
  only if the app genuinely shows no feedback.
- **`AMBIGUOUS_TARGET`** — good. Add `region`, `within`, `near` or `index`.
- **Screenshots fail on API < 30** — `UNSUPPORTED`. There is no fallback by design.
