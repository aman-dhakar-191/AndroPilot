# Plan: telemetry, agent app, MCP, skills

Status: **implemented**. Kept as the record of why each piece is shaped the way it is.
See [AGENT_HOST.md](AGENT_HOST.md) for how to actually run it.

Two things were built differently from this plan, both simplifications found while writing
the code:

- **Telemetry reuses `TraceRecorder` instead of reimplementing the record format.** The
  recorder already produces exactly the redacted JSON Lines the server wants, so the
  telemetry module is a `TraceWriter` that spools and uploads. One definition of a record,
  one definition of redaction, and a telemetry batch is byte-identical to a local trace.
- **The WebSocket is hand-written rather than taken from a library.** Android has no
  `java.net.http`, so a library would have meant one implementation on the phone and a
  different one on the desk. Client and server are tested against each other on loopback.

This covers four asks, in the order they should be built:

1. Server telemetry — everything logged centrally, for analysis and for judging whether the
   AI made the right call.
2. An agent app that talks to a model running on the user's own PC, at a configurable
   endpoint.
3. MCP support.
4. Agent skills support.

## The constraints that shape all four

- **`andropilot-core` never gains an Android dependency.** Anything with a socket, a
  `Context`, or a notification lives above the boundary.
- **"The SDK has no network code; do not add any"** (CLAUDE.md). Telemetry and the agent
  transport therefore cannot live in `:andropilot-core` or `:andropilot-android`. They
  become sibling modules, the way `:andropilot-devtools` already is — which is also exactly
  what "keep separate and easy to integrate" asks for.
- **Redaction is opt-out.** Screen text, typed values, and the *prose the SDK composes from
  a screen* (match reasons, diff summaries, failure messages) are withheld unless the host
  opts in. A telemetry module that ships screen content by default would invert the
  project's central privacy rule.
- **`AgentEventListener` is synchronous and never dropped.** Anything registered there must
  enqueue and return. Doing I/O on that call blocks the action loop.
- **`ToolCodec.executeJson(session, payload) : String` is already the entire remote-agent
  transport contract.** Both the agent app and MCP are adapters over it, not new engines.
- **A phone is not addressable from a PC.** Behind carrier NAT there is no inbound route.
  Whatever connects must be dialled *out* by the phone.

---

## 1. Server telemetry — `:andropilot-telemetry`

### Shape

A new module, depending on `:andropilot-core` only (for `AgentEvent`), never depended on by
it. Integration is one line, mirroring `TraceRecorder`:

```kotlin
SessionConfig(
    listeners = listOf(
        TelemetrySink.to("https://box.local:8443/ingest", deviceKey = BuildConfig.KEY),
    ),
)
```

`TelemetrySink` implements `AgentEventListener`. `onEvent` does nothing but append to an
in-memory ring buffer and return; a background coroutine drains it.

### Pipeline

```
AgentEvent -> ring buffer (bounded, drops oldest with a counted gap marker)
           -> JSONL spool file on disk (survives crash/reboot/offline)
           -> batched POST (gzip, N events or T seconds, whichever first)
           -> exponential backoff with jitter on failure; spool is the retry queue
           -> fsync'd offset file, so a crash re-sends at most one batch, never zero
```

Reuse `RecordingOptions` verbatim for redaction — `includeText = false`,
`includeSnapshots`, `maxSnapshotElements`. Same knobs as the file recorder means one mental
model and one place where "what leaves the device" is decided. Default stays redacted; the
user opts in per-deployment.

Each batch carries a `runId` (one per session), a `deviceId` (random, persisted, **not** an
ANDROID_ID or any hardware identifier), and an app/SDK version.

### Server

Deliberately small and boring — this is a personal/self-hosted analysis tool, not a product:

- Single-binary ingest service (Ktor, so it shares the Kotlin toolchain and
  `kotlinx.serialization` models with the client; a Python/FastAPI equivalent is fine if
  you prefer the analysis ecosystem).
- Append to date-partitioned JSONL on disk. That is already the format the trace tooling
  reads, so every analysis script written against device traces works unchanged.
- Bearer-token auth per device; TLS mandatory. Reject unknown tokens with 401 and log the
  attempt.
- Optional second hop into DuckDB/SQLite for querying. Do **not** start with a database —
  the JSONL is the source of truth and rebuilds any index.

### Answering "did the AI make the correct decision?"

Raw events do not answer that on their own. The evaluation layer needs three things the
event stream already contains or can cheaply carry:

- **Action → outcome pairs.** `ActionStarted`/`ActionFinished` already give the action, the
  `FailureReason`, and the duration.
- **What the screen looked like before and after.** `SnapshotCaptured` plus the existing
  `UiDiff` gives "the AI tapped X and the screen changed to Y" — the core signal for
  correctness.
- **What the AI *intended*.** This is the missing piece. Add an `AgentEvent.Note` carrying
  the model's stated goal/reasoning at each step, emitted by the agent app (not the SDK).
  Correctness is intent-versus-outcome; without intent you only have a crash log.

Derived metrics worth computing server-side from day one: per-action failure rate by
`FailureReason`, `STALE_ELEMENT` rate (the reliability canary), `AMBIGUOUS_TARGET` rate
(the perception canary), steps-per-completed-task, and retry loops (the same action against
the same target three times = the model is stuck).

### Tradeoffs

- Ktor server adds a JVM dependency to the repo but keeps one language. A Python server
  would be faster to write ad-hoc analysis against; pick by where you will spend time.
- JSONL-first means queries are slow at scale. At personal-device volume that is
  irrelevant, and it is trivially reversible.
- **The honest risk:** a telemetry module in this repo makes it one flipped default away
  from a component that streams screen contents off-device. Mitigation: `includeText`
  defaults false, the sink refuses plaintext `http://` unless a loopback/private address,
  and the README says plainly what each option exports.

---

## 2. Agent app — `:andropilot-agent` (app) + a PC-side host

### Why the phone dials out

A model on your PC cannot open a connection to your phone: carrier NAT, changing IPs,
Doze. So the phone opens and holds a **WebSocket** to the PC host, and the PC sends action
frames down it. This also makes "configurable endpoint" natural — it is just the URL the
phone dials.

```
[ your model ] <-> [ PC host process ] <==WSS==> [ Android agent app ] -> AndroPilotSession
```

### Frames

Thin envelope over what already exists:

```
PC -> phone : {"id":"7","kind":"action","payload": <ToolCodec action JSON>}
phone -> PC : {"id":"7","kind":"result","payload": <ToolCodec result JSON>}
phone -> PC : {"kind":"event","payload": <AgentEvent JSON>}      // live observability
PC -> phone : {"kind":"tools"}  /  phone -> PC : toolDescriptors()
```

`payload` is passed straight to `ToolCodec.executeJson`. No second schema, no drift.

### Configuration

Settings screen, persisted in DataStore: endpoint URL, auth token, TLS trust (system CA or
a pinned self-signed cert for a home LAN box), reconnect backoff, and the
`SafetyPolicy` selection (defaults to `financialOnly()`, matching current demo behaviour).
Endpoint is editable at runtime — never a build constant.

### Security boundary — the part to get right

A socket that drives an accessibility service is the most dangerous surface in this
project. Non-negotiables:

- WSS only. Plain `ws://` permitted solely for loopback/`10.`/`192.168.` during
  development, and the UI must say it is unencrypted.
- Mutual auth: a long random device token, plus certificate pinning for self-signed home
  servers. A token alone over an unpinned TLS connection to a LAN IP is phishable.
- A persistent, non-dismissable notification whenever the socket is connected. The user
  must always be able to see that something remote can drive their phone.
- A kill switch in the notification and in the app.
- `SafetyPolicy` is enforced **on the phone**, never negotiated over the wire. The PC asks;
  the phone decides. A compromised host must not be able to raise its own privileges.
- Rate-limit inbound actions; refuse a burst that looks like a script, not an agent.

### Foreground/lifecycle

The agent runs as a foreground service (`connectedDevice` or `dataSync` type). Expect
OEM battery killers; reconnect with backoff and surface "disconnected" honestly rather than
silently retrying forever.

---

## 3. MCP support

**Recommendation: implement the MCP server on the PC, not on the phone.** [Likely — based
on MCP's transport model, where a server is a local process or an HTTP endpoint; a
NAT'd phone is neither.]

The PC host from §2 additionally exposes a stdio (and optionally streamable-HTTP) MCP
server. Then any MCP-speaking client — Claude Code, Claude Desktop, your own runtime —
drives the phone with zero Android work.

The mapping is almost free:

| MCP | AndroPilot |
|---|---|
| `tools/list` | `ToolCodec.toolDescriptors()` — `name`, `description`, `parameterSchema` is already JSON Schema |
| `tools/call` | forward `arguments` as the action payload to `executeJson` over the socket |
| tool result content | `ToolCodec`'s compact result rendering |
| `resources` | current snapshot, last screenshot |

`ToolDescriptor.maxRisk` has no MCP equivalent; surface it in the description text and keep
the real enforcement on the phone.

Effort: small — a few hundred lines in the host, because `toolDescriptors()` was designed
for exactly this. **This is the highest value-per-line item in the whole plan** and is the
reason to build the host process before anything fancy.

Caveat to verify before writing code: confirm the current MCP spec revision and the exact
`tools/list` / `tools/call` payload shapes against the live spec rather than from memory.

## 4. Agent skills

Feasible and genuinely useful, because Android automation failures are overwhelmingly
app-specific ("Settings buries Wi-Fi two levels deep", "this app's Continue button is an
unlabelled icon").

A skill is a per-package bundle, keyed on `snapshot.packageName`:

```
skills/com.android.settings/SKILL.md      # prose hints injected into the model's context
skills/com.android.settings/selectors.json # known aliases: "wifi" -> resourceId ...
```

Two mechanisms, both host-side:

- **Context injection**: when the foreground package changes, the host prepends that
  package's `SKILL.md` to the model's system context. Cheap, no SDK change.
- **Selector hints**: a package-scoped alias table consulted by the matcher before general
  text scoring. This *does* touch core, and should be a pure-Kotlin,
  unit-testable `SelectorHints` input to the existing matcher — not a special case sprinkled
  through it.

Start with context injection only. It needs no SDK change, and it will tell you whether
selector hints are even necessary.

---

## Build order

1. **PC host + MCP server + WebSocket protocol** (the plumbing everything else rides on).
2. **`:andropilot-agent` app** — socket client, foreground service, config screen, kill
   switch.
3. **`:andropilot-telemetry` + ingest server** — the sink is trivial once events are
   already being framed for §2.
4. **Skills** — context injection first, selector hints only if measurements demand it.

§1 and §2 share the framing code, so building the host first means telemetry is nearly free
afterwards rather than a parallel mechanism. That ordering is also the one that respects
"one event stream, many sinks".

## Open questions to settle before implementation

- Where does the host process live — this repo (a `host/` Kotlin or Python module) or a
  separate one? A separate repo keeps the Android build clean; same repo keeps the protocol
  definition in one place. Leaning same repo, own module, no dependency edge to the SDK.
- Does the model run agentically on the PC (it loops, phone executes), or does the phone
  own the loop? Recommend the PC — it makes model swapping a host-side concern and keeps
  the phone a pure executor.
- Telemetry retention and whether traces ever include text in practice.
