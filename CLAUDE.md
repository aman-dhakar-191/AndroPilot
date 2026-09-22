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
  `allowTextInLogs`. This component can read every screen. What that flag governs is what
  *leaves* the device through a log or a sink; showing a person their own screen's contents
  on their own phone, in a confirmation prompt or the agent's activity list, is the opposite
  of a leak and is not gated by it.
- **One event stream, many sinks.** `AgentEvent` is where observability is produced;
  `results` and `snapshots` are filtered views of it, and the recorder and the Logcat
  listener are just listeners. Do not add a second parallel mechanism -- there were four
  before this was unified. Config-registered listeners are synchronous and never dropped; the
  flow may drop.
- **A settings field must not be able to stop the app starting.** `TelemetrySink.http`
  rejects an endpoint it will not post to, and that rejection used to escape
  `Application.onCreate` -- so a mistyped host crashed the agent on every launch, with the
  app unable to open and the field therefore uncorrectable short of wiping its data.
  Anything optional that is constructed at startup from user input gets wrapped, and the
  failure is shown on the screen instead.
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
- **A policy that asks needs somewhere to answer.** `financialOnly()` leaves a financial
  action pending rather than refusing it, and pending confirmations never expire -- so an
  app that gates on it and ships no approval UI has not built a safety gate, it has built a
  permanent stall with nothing on the device saying why. The agent app went out that way
  once. Any host using a confirming policy has to surface `pendingConfirmations()` and call
  `resolveConfirmation`.
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
- **The device slot is released for a closing session, not just a closed one.** A phone
  that drops and immediately redials arrives while the previous session is still unwinding:
  the socket is dead but the thread has not reached the `finally` that clears the slot. A
  bare "already connected" check refuses the device on behalf of *itself*, and nothing
  retries -- which passes every test on an idle machine and fails on a loaded one, because
  it is a race and not a timeout. A new connection waits out `HANDOVER_GRACE_MS` for the
  slot; a genuinely concurrent second phone still holds an open socket, so it is still
  refused.
- **A connection is held by a foreground service so it cannot be invisible.** While the
  socket is open another machine can read and tap the screen; the ongoing notification, and
  the Disconnect action on it, are the point of putting it there rather than in the
  activity.

- **Nothing in the SDK or on the phone ever calls a model; the host's loop does.** MCP
  does not fill that gap and cannot: an MCP server answers a client that already owns a
  model, so pointing one at a model endpoint is backwards. `AgentLoop` is the other half,
  and both paths stay -- MCP when a client drives, the loop when the host does.
- **The model is the untrusted party.** It is text from a server, driving a real phone.
  Its tool calls go through the device's `SafetyPolicy` exactly as an MCP client's do,
  nothing host-side can widen that, and `AgentLoop` has a hard step ceiling because a model
  that has misread a screen will otherwise keep trying forever.
- **Host settings live in `~/.andropilot-host/settings.json`, and the command line always
  wins over them.** The home directory rather than the checkout because the token is in the
  file, and one kept beside the working copy is lost whenever the repository is moved --
  which means reconfiguring the phone by hand. A malformed file is refused, never ignored:
  falling back to defaults would start on a different port with a newly invented token, and
  the only symptom would be a phone that stopped connecting.
- **Model endpoint, key and name are configuration, never compiled in**, and the code
  behind them is the `ModelClient` interface. A provider-neutral SDK with a vendor wired
  into the host in front of it would be neutral in name only. Prefer `ANDROPILOT_MODEL_KEY`
  over the flag: an argument is readable by anything that can list processes. `modelId` is
  sent verbatim and is not assumed to name a model -- a gateway may answer to a group name
  instead (an OmniRoute combo, for instance), and a host that validated the string would
  reject a configuration that works. The control page offers `GET {endpoint}/models`
  instead, because the gateway is the only thing that knows its own names and a wrong one
  costs a failed run that reports nothing useful. `ModelCatalog` failing is a value, never
  an exception: an endpoint without `/models` is ordinary and must not stop the page.
  It reads the gateway's own combo API too, because `/v1/models` is the OpenAI-shaped
  catalogue and never carries a gateway's groups -- a list of a hundred models that
  confidently omits the one name somebody configured is worse than no list at all. A
  gateway's groups are in that listing, tagged `owned_by`, and the listing is
  per-credential: unauthenticated, the same endpoint answers with public models and no
  combos at all. So `Catalog.listed` records whether the endpoint would say, and nothing
  may call a typed name invalid without it -- a wrong key produces a failure phrased as an
  unknown *model*, and telling somebody their working combo is misspelled sends them to
  fix the one thing that was right. **Blank counts as absent** when reading
  `ANDROPILOT_MODEL_KEY`: `?:` tests only for null, so an empty or stale environment
  variable beat the settings file and the host went unauthenticated -- which presents as a
  missing combo, not as an auth failure. The startup line names the key's source and
  length, never the key, because it is otherwise the one invisible input.
- **A model that cannot call tools cannot drive a phone**, and says so by talking until the
  step ceiling rather than failing. `capabilities.tool_calling` is checked at startup and
  at selection. Absent, it is assumed true: refusing on silence would rule out every
  endpoint that publishes no capabilities.
- **The control UI binds to loopback, always, whatever `--bind` says.** `--bind` widens the
  agent socket so a phone on the LAN can dial in; the page that starts runs is a different
  thing and carries no password *because* only this machine can reach it. Those two
  decisions are joined and must not drift apart.
- **`RunEvent` is the loop's stream and `AgentEvent` is the device's.** They stay separate:
  one is what the host did, including the model's reasoning, and the other is what the
  phone observed and is the record telemetry keeps. Merging them would have the host
  inventing device events it never saw.
- **Intent is recorded on the device, not the host.** `Frame.Note` carries the model's
  reasoning down so it becomes an `AgentEvent.Note` in the same ordered stream as the
  outcome. Nothing afterwards can judge whether a decision was *correct* from the action
  alone -- a tap looks identical whether it was reasoned or guessed -- and lining intent up
  with outcome only works if they share one stream.
- **Results are summarized on the device too.** `Frame.ActionResponse.summary` is
  `ToolCodec.summarizeForModel`, produced where core lives. A full result carries an entire
  snapshot and is far too large for a model's context every turn, and rendering it
  host-side would mean parsing payloads the host deliberately treats as opaque.

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
- **The release version is derived, not typed.** A manual run reads the newest `v*` tag and
  bumps the patch; `Release: minor` / `Release: major` trailers in the commits since that tag,
  or the workflow's dropdown, raise it further. Commit subjects here are imperative prose by
  design, so nothing in a normal commit can imply a major or a minor -- which is exactly why
  the default is patch and anything larger is stated rather than guessed. A tag push still
  wins outright. The step refuses a version that already has a tag, and one whose minor or
  patch has reached 100, because that would break the versionCode packing below.
- **A release carries an APK per app, and the updater has to be told which is its own.**
  `AppUpdates.configure(repository, apkAsset = ...)`. The GitHub API returns assets sorted
  by name, so "the first one ending in .apk" -- correct while the repository shipped one app
  -- silently became "whichever sorts first" when the agent was added, and the inspector
  spent several releases installing the agent over itself. Both apps share a signing key, so
  it succeeded and nothing reported a problem. With more than one candidate and no hint the
  updater now refuses rather than guesses, and `install` re-reads the downloaded archive's
  own package name before handing it to the installer.
- **The demo's versionCode packing is duplicated** in `demo/build.gradle.kts` and
  `AppUpdater.versionCodeOf`. If they diverge, a newer release looks older than what is
  installed and the in-app update is silently never offered.
- **The permission to install packages lives in `:andropilot-updater` and nowhere else.**
  It is its own app with no SDK, no accessibility service and no automation code, and it is
  the only thing on the device declaring `REQUEST_INSTALL_PACKAGES`. The inspector used to
  declare it while also running an accessibility service, which is the pair this project
  calls a malware primitive; splitting them means no single component has both. An updater
  that can only update itself has to live inside every app it serves, which is why
  `AppUpdater` takes an `UpdateTarget` rather than assuming `context.packageName`.
- **Updating an app switches off its accessibility service.** Replacing a package kills its
  processes, the binding is recorded as crashed, and Android never rebinds -- see the
  gotcha below. The updater says so before installing; anything else that replaces these
  apps should too, because the symptom is indistinguishable from a broken install.
- **The updater library lives in `:andropilot-devtools`, never the SDK, and depends on neither.**
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
- **A dead accessibility binding is sticky, and Android never retries it.** When the
  service's process is reclaimed while bound, `AccessibilityManagerService` moves the
  component into `Crashed services:` and leaves it there: `dumpsys accessibility` then shows
  it under `Enabled services:` with an empty `Binding services:`, so the UI reads "switched
  on" forever while nothing runs. Only re-enabling clears it. Diagnose with
  `adb shell dumpsys accessibility` and read three lines -- `Bound`, `Crashed`, `Binding` --
  rather than hunting Logcat for a crash that already happened and left no trace. Clear it
  without the settings UI by rewriting `enabled_accessibility_services` without the
  component and then with it.
- **Every app built on this SDK appears in the same accessibility list**, distinguished only
  by `andropilot_service_label`. The library's value is a DEFAULT and each app overrides it;
  when two apps both shipped the default, the two rows were identical and the user could not
  tell which one "switch it off and on again" was actually touching. That is what made a
  dead binding unfixable through the UI.
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
