# AndroPilot

[![Build](https://github.com/aman-dhakar-191/AndroPilot/actions/workflows/build.yml/badge.svg)](https://github.com/aman-dhakar-191/AndroPilot/actions/workflows/build.yml)

An Android SDK that gives an external AI agent **hands and eyes** on a device.

AndroPilot is the execution and perception layer. It does not contain a model, a prompt, or
a provider SDK. The AI decides *what* should happen; AndroPilot decides *how* that happens
safely and reliably on Android, and reports back in enough structural detail for an agent to
know whether it worked.

```kotlin
AndroPilot.initialize(application)
val session = AndroPilot.session()

session.observe()                                   // what is on screen?
session.click(Selector.text("Sign in"))             // act semantically, not by coordinate
session.typeText("sam@example.com", Selector.id("email"))
session.waitFor(UiCondition.TextVisible("Welcome"))  // verify, don't assume
```

## What it gives an agent

| Capability | Action |
|---|---|
| Understand the screen | `observe` — a normalized, compact element list |
| Inspect and locate | `find_element`, `element_exists` |
| Interact | `click`, `long_press`, `type_text`, `clear_text`, `scroll`, `scroll_until`, `swipe` |
| Navigate | `press_key` (back/home/recents), `launch_app`, `open_intent` |
| See pixels | `screenshot`, plus a pluggable `VisionProvider` |
| Synchronise | `wait_for`, `verify`, `sleep` |

Every action returns a structured result that says whether it succeeded, *why* it failed,
what was actually found, whether the UI changed, whether the target was ambiguous, and
whether a human needs to confirm.

## Design in one paragraph

The interesting part of an automation SDK is not sending a tap — it is knowing that the tap
landed on the right thing and that something happened. So the whole reliability layer
(element matching, gesture geometry, change detection, settle waiting, retries, safety
classification) lives in **`andropilot-core`**, a pure Kotlin/JVM module with no Android
dependency, behind a single `UiDriver` interface. Android is one implementation of that
interface. This is why the SDK's logic has 143 unit tests that run in seconds with no
emulator, and why a future remote-control transport is a new `UiDriver` rather than a
rewrite.

## Modules

```
andropilot-core      Pure Kotlin/JVM. Models, selectors, matching, actions, verification,
                     safety, tool codec, and a fake driver for consumer tests.
andropilot-android   AccessibilityService-backed UiDriver, plus the AndroPilot facade.
demo                 An inspector app for validating the SDK on a real device.
```

`andropilot-core` builds and tests with **no Android SDK installed** — the Gradle settings
script only wires in the Android modules when one is present.

## Getting started

- **[docs/INTEGRATION.md](docs/INTEGRATION.md)** — adding the SDK to an app, permissions,
  and the first working call.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — why it is shaped this way, the Android
  platform limits that forced each decision, and the extension points.
- **[docs/AGENT_INTEGRATION.md](docs/AGENT_INTEGRATION.md)** — wiring the action set up as
  tools for any LLM runtime.

## Building

```bash
./gradlew :andropilot-core:build          # core + its full test suite, no Android SDK needed
./gradlew :andropilot-android:assembleRelease   # requires an Android SDK
./gradlew :demo:assembleRelease
```

Releases are built and published by GitHub Actions — push a `v*` tag, or run the **Release**
workflow manually.

## Status and limits

This is a working MVP with a clean architecture, not a finished product. Known boundaries:

- Screenshots require API 30+. There is no `MediaProjection` fallback, by choice: it would
  demand a second, more intrusive consent for an optional capability.
- Screens marked `FLAG_SECURE` cannot be captured, and some expose no accessibility tree.
  Both are reported explicitly rather than worked around.
- The SDK respects Android's security model. It contains nothing intended to bypass platform
  or app protections, and it cannot grant itself the accessibility permission.
- The default sensitive-action classifier reads structure first (password fields, dialog
  commit buttons) and labels second, and it is still a heuristic: it will miss a destructive
  button with an unusual label, and flag the occasional harmless one. Supply your own
  `SafetyPolicy` when the cost of either mistake is real.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
