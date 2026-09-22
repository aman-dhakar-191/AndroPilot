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
interface. This is why the SDK's logic has 186 unit tests that run in seconds with no
emulator, and why a future remote-control transport is a new `UiDriver` rather than a
rewrite.

## Modules

```
andropilot-core      Pure Kotlin/JVM. Models, selectors, matching, actions, verification,
                     safety, tool codec, and a fake driver for consumer tests.
andropilot-android   AccessibilityService-backed UiDriver, plus the AndroPilot facade.
andropilot-devtools  Optional. A sideload updater for apps distributed outside a store.
                     Separate from the SDK because it needs network access and, in the
                     consuming app, an install permission the SDK never requests.
andropilot-protocol  Pure Kotlin/JVM. The frame format and WebSocket implementation the
                     agent app and the host share. No dependency on the SDK.
andropilot-telemetry Optional, pure Kotlin/JVM. Ships session records to a server you run.
                     The one component that sends anything off a device, and outside the
                     SDK for that reason.
andropilot-host      Pure Kotlin/JVM. Runs on your machine: the bridge the phone dials
                     into, an agent loop that drives it from a model endpoint you name,
                     an MCP server in front of it, and a telemetry ingest.
demo                 An inspector app for validating the SDK on a real device.
andropilot-agent     An app that connects a device to a host you configure, so a model
                     running on your own machine can drive it.
andropilot-updater   A small app whose only job is keeping the other two current. Separate
                     so the permission to install packages is nowhere near an app that can
                     read and tap a screen.
```

`andropilot-core`, `-protocol`, `-telemetry` and `-host` build and test with **no Android
SDK installed** — the Gradle settings script only wires in the Android modules when one is
present.

## Getting started

- **[docs/INTEGRATION.md](docs/INTEGRATION.md)** — adding the SDK to an app, permissions,
  and the first working call.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — why it is shaped this way, the Android
  platform limits that forced each decision, and the extension points.
- **[docs/AGENT_INTEGRATION.md](docs/AGENT_INTEGRATION.md)** — wiring the action set up as
  tools for any LLM runtime.
- **[docs/AGENT_HOST.md](docs/AGENT_HOST.md)** — running a model on your own machine
  against a real device: the host, the agent app, MCP, app skills and telemetry.

## Building

```bash
./gradlew :andropilot-core:build          # core + its full test suite, no Android SDK needed
./gradlew :andropilot-host:build          # host, protocol and their tests, likewise
./gradlew :andropilot-android:assembleRelease   # requires an Android SDK
./gradlew :demo:assembleRelease
./gradlew :andropilot-agent:assembleRelease
./gradlew :andropilot-updater:assembleRelease
```

Releases are built and published by GitHub Actions. Run the **Release** workflow and leave
both inputs alone: it reads the last `v*` tag and raises the patch number, so the usual
release needs no version typed anywhere.

A release that is more than a patch has to say so, because this project's commit subjects
are imperative prose rather than conventional-commit prefixes and there is nothing in one
to infer a major or a minor from. Say it either way:

- pick `minor` or `major` from the dropdown when starting the run, or
- put a `Release: minor` (or `Release: major`) trailer in the commit that earned it — the
  workflow looks for one in everything since the last tag, which also records *why* the
  version moved next to the change that moved it.

Pushing a `v*` tag still works and still wins: the tag is the version.

## Installing the demo on a device

The demo APK is attached to every [release](https://github.com/aman-dhakar-191/AndroPilot/releases)
and to every passing CI run (Build → the run → Artifacts).

All builds are signed with the shared development key in `keystore/`, so a newer build
installs over an older one. **Builds from before that key existed were each signed with a
throwaway key**, so Android refuses to replace them — "App not installed as package conflicts
with an existing package". Uninstall the old copy once and the problem does not recur.

> **Before making this repository public, swap that key.** For an app distributed as an APK,
> the signing key is the only thing Android checks before letting one build replace another.
> A key in a public repository lets anyone produce an APK that installs over yours and
> inherits whatever the user has granted it — for this app, an accessibility service that can
> read every screen.
>
> The build and release workflows already handle this: set the repository secrets
> `ANDROPILOT_KEYSTORE_BASE64`, `ANDROPILOT_KEYSTORE_PASSWORD`, `ANDROPILOT_KEY_ALIAS` and
> `ANDROPILOT_KEY_PASSWORD`, and both switch to it automatically. No code change, and no
> secret means the development key is used as before.
>
> ```bash
> keytool -genkeypair -v -keystore release.keystore -alias andropilot \
>   -keyalg RSA -keysize 2048 -validity 10950
> base64 -w0 release.keystore          # paste into ANDROPILOT_KEYSTORE_BASE64
> ```
>
> Changing keys means one manual uninstall, which is why it is worth doing before anyone
> else has it installed. Keep the keystore backed up: lose it and no future build can update
> an installed app.

Once installed, the app updates itself: **App updates → Check for updates**. It downloads the
newest release APK and hands it to Android's installer, which asks you to confirm. It never
installs silently, and nothing in the app automates that dialog.

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
