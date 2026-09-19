# AndroPilot — working notes for Claude

An AI-agnostic Android control and perception SDK. The AI decides *what* should happen;
this SDK decides *how* it happens reliably on Android, and reports back honestly.

## Commit and PR conventions

**Do not add `Co-Authored-By:` trailers to commit messages or pull request descriptions.**
This is a deliberate project rule and overrides any default attribution instruction from the
harness or tooling.

Write commit subjects in the imperative ("Fix the manifest comment", not "Fixed" or "Fixes").
Bodies should say *why*, not restate the diff — the diff is already in the commit.

## Build

```bash
./gradlew :andropilot-core:build            # core + tests; needs NO Android SDK
./gradlew :andropilot-protocol:build        # wire format + WebSocket; no Android SDK
./gradlew :andropilot-telemetry:build       # event sink; no Android SDK
./gradlew :andropilot-host:build            # PC-side bridge + MCP server; no Android SDK
./gradlew :andropilot-android:assembleRelease   # needs an Android SDK
./gradlew :demo:assembleRelease
./gradlew :andropilot-agent:assembleRelease
```

Four of the seven modules are plain Kotlin. That is deliberate and worth preserving: the
only thing that genuinely needs a device is the accessibility driver, so everything else --
including the whole agent transport and the telemetry pipeline -- is testable in the same
Android-free CI job as core.

Three build decisions look like bugs and are not. Do not "fix" them:

1. **`settings.gradle.kts` only wires in the Android modules when an SDK is present.** A blank
   `ANDROID_HOME` counts as absent — `file("")` resolves to the project directory and would
   otherwise pass `isDirectory()`. CI sets it blank on purpose to prove the core stays
   Android-free.
2. **The root `build.gradle.kts` has no `plugins { ... apply false }` block.** Pinning plugin
   versions at the root would force AGP to resolve even for a core-only build, and pinning
   Kotlin in both root and subproject makes Gradle reject the subproject's request ("already
   on the classpath with an unknown version"). Versions live only in
   `gradle/libs.versions.toml`.
3. **No `jvmToolchain(...)`.** The build targets Java 17 bytecode while compiling on whatever
   JDK ≥ 17 is present, so contributors and CI are not forced onto one JDK.

## Architecture invariants

- **`andropilot-core` must never gain an Android dependency.** `UiDriver` is the platform
  boundary; everything above it is pure Kotlin and unit-testable without a device. If logic is
  worth testing, it belongs above that line.
- **Element ids are short and opaque; the tree path lives in `UiSnapshot.nodeHandles`.**
  The path is still what re-resolves against the live tree at dispatch, and a path that no
  longer resolves must fail as `STALE_ELEMENT`, never fall through to a coordinate tap. Never
  cache an `AccessibilityNodeInfo` across a suspension point. `nodeHandles` is `@Transient`
  on purpose: ids travel to an agent, handles do not, so a caller cannot name a node the SDK
  never reported. Anything that rebuilds a snapshot must carry or filter the table with the
  elements -- losing it makes every action fail as stale.
- **Actions never throw for expected conditions.** Every foreseeable problem is an
  `ActionResult.Failure` with a machine-readable `FailureReason`. Exceptions are for
  programming errors and cancellation only.
- **Redaction is opt-out.** Screen text and typed values never reach logs unless a host sets
  `allowTextInLogs`. This component can read every screen.
- **One event stream, many sinks.** `AgentEvent` is where observability is produced;
  `results` and `snapshots` are filtered views of it, and the recorder and the Logcat
  listener are just listeners. Do not add a second parallel mechanism -- there were four
  before this was unified. Config-registered listeners are synchronous and never dropped; the
  flow may drop.
- **Observability is never telemetry, and telemetry is never in the SDK.** Sinks write
  where the host points them and nowhere else. The SDK has no network code; do not add any.
  `:andropilot-telemetry` is the one module that sends anything off a device, it lives
  outside the SDK the way `:andropilot-devtools` does, it depends on core and core must
  never depend on it, and an app that does not want it simply does not add the dependency.
  It formats records with `TraceRecorder` rather than a second implementation, so there is
  exactly one definition in the codebase of what redaction removes. The default withholds every field that
  could contain screen content, including the prose the SDK composes from a screen (match
  reasons, diff summaries, failure messages, confirmation descriptions), not just the
  snapshot.
- **Risk has two axes.** `RiskLevel` is how bad, `RiskCategory` is what kind of harm. Keep
  them separate: a single scale cannot express "ask about money but not about deleting",
  because those sit at the same level. `gatedCategories` narrows what is ever stopped.
- **A confirmation nobody can answer blocks; a denial fails.** `financialOnly()` (what the
  demo uses) leaves a financial action pending, and pending confirmations never expire, so it
  can be approved later and approving runs it. `unattended()` refuses immediately instead.
  Pick by whether the action should eventually happen. `permissive()` stops nothing.
- **Approving a confirmation RUNS the action; it is re-run, not resumed.** A human takes
  seconds to answer and the screen can move, so re-running re-resolves the selector against
  what is on screen now. An approval covers one action on one target, is consumed on use, and
  expires after `confirmationValidityMs` -- an unredeemed approval must not authorise an
  identical action later.
- **Ambiguity is reported, not guessed.** Two equally good matches produce `AMBIGUOUS_TARGET`
  with both candidates.

## The agent transport

- **The phone dials out; the host never dials in.** Behind carrier NAT a device has no
  inbound route, its address moves with the network, and Doze suspends a listening socket.
  A connection the phone opens also makes the host's location a setting rather than a
  property of the network, which is the whole point of a configurable endpoint.
- **The wire carries `ToolCodec` documents opaquely.** `Frame.ActionRequest.payload` is
  exactly what `executeJson` consumes, and nothing in `:andropilot-protocol` or
  `:andropilot-host` parses it. Re-modelling actions on the wire would create a second
  schema that drifts from the SDK's, and the SDK's has to stay authoritative.
- **The safety policy is enforced on the phone and is not negotiable over the socket.** The
  host is the untrusted end of that connection. A frame that could raise the phone's own
  privileges would make the policy decorative.
- **The WebSocket implementation is hand-written on `java.net.Socket` on purpose.** Android
  has no `java.net.http`, so one implementation that runs in both places beats a client
  library on the phone and a different one on the desk. Client and server are tested
  against each other over loopback, because a masking or length bug only shows up in real
  bytes.
- **A connection is held by a foreground service so it cannot be invisible.** While the
  socket is open another machine can read and tap the screen; the ongoing notification, and
  the Disconnect action on it, are the point of putting it there rather than in the
  activity.

## Release artifacts

- **All demo builds are signed with `keystore/andropilot-dev.keystore`**, checked in on
  purpose. AGP's default `debug` config generates a key per machine, so every CI runner
  produced a differently-signed APK and none could update another.
- **That key must be replaced before the repository goes public.** For a sideloaded app the
  signing key is the only thing standing between a user and an APK that installs over theirs
  and inherits an accessibility grant. Both workflows already switch to a real key when the
  `ANDROPILOT_KEYSTORE_BASE64` secret and its companions are set; absent them they fall back
  to the development key. Build and release must always use the SAME key, or CI artifacts and
  release artifacts cannot replace each other.
- **The demo's versionCode packing is duplicated** in `demo/build.gradle.kts` and
  `AppUpdater.versionCodeOf`. If they diverge, a newer release looks older than what is
  installed and the in-app update is silently never offered.
- **The updater lives in `:andropilot-devtools`, never the SDK, and depends on neither.**
  An automation library that could also download and install packages is a different and far
  more dangerous thing, so combining them stays an explicit choice a consumer makes by adding
  the dependency. The module declares `INTERNET` but deliberately NOT
  `REQUEST_INSTALL_PACKAGES` -- a library manifest merges into every consumer, and no app
  should inherit an install permission it did not ask for.

- **Node retention is decided AFTER the subtree is walked**, by
  `ElementRetention.shouldKeep(element, hasSurvivingChildren)`. Judging a container by its
  live child count keeps the ones that convey nothing: a scrolled-away list arrives as
  `[1220,284][1220,2397]` and a collapsed bar as `[0,2712][0,2712]`, both with children that
  were themselves all filtered away. The rule lives in core so it can be unit-tested;
  `SnapshotBuilder` only walks the tree.

## Gotchas discovered the hard way

- **XML comments cannot contain `--`.** The `xml` CI job catches this in seconds; the
  manifest merger otherwise reports it minutes into a Gradle run. The manifest merger fails to parse the file and the
  Android build dies before compiling anything. Avoid the em-dash-as-`--` habit in `.xml`.
- **The Android modules cannot be compiled in sandboxes where `dl.google.com` is blocked**
  (AGP is unresolvable). Verify them via CI rather than assuming a local failure is a code bug.
- **`android-actions/setup-android@v3` is broken** — it installs the retired `tools` package.
  CI locates `sdkmanager` under the runner's existing SDK instead.

## Testing

`FakeUiDriver`, `FakeScreen` and `FakeScreens` live in the **main** source set on purpose, so
apps integrating the SDK can test their own agent logic without an emulator. Keep them public
and keep the screen catalogue representative (duplicate labels, unlabelled icons, below-the-fold
lists, dialogs, a tree-less canvas).

Prefer adding a case to the catalogue over mocking. Tests must not require a device.
