# Running a model against your own device

Three pieces, in the order you start them:

```
[ your model ]  <--MCP (stdio)-->  [ andropilot-host ]  <==WebSocket==>  [ agent app ]  -->  AndroPilotSession
       PC                                 PC                              the phone
```

The **phone dials out**. That is not a preference: behind carrier NAT a device has no
inbound route, its address changes as it moves between networks, and Doze suspends a
listening socket. It also means the host's location is a setting rather than a property of
your network, which is what makes "the model runs on my desktop today and a VPS tomorrow"
a text field instead of a rebuild.

## 1. Start the host

```bash
./gradlew :andropilot-host:installDist
./host/build/install/andropilot-host/bin/andropilot-host \
    --port 8765 \
    --token "$(openssl rand -base64 24)" \
    --skills ./skills \
    --ingest-port 8766
```

It prints the token it is using. Everything binds to `127.0.0.1` unless you pass `--bind`;
widen that deliberately, because a socket into this process can drive a phone.

## 2. Point the phone at it

Install `andropilot-agent`, enable the accessibility service, then fill in:

- **Host endpoint** — `ws://192.168.1.20:8765/agent`, or `wss://...` once you have a
  certificate. Use `wss://` wherever you can: over `ws://` the token and every screen the
  agent observes cross the network in the clear.
- **Shared token** — what the host printed.

Press Connect. A permanent notification appears for as long as the socket is open, with a
Disconnect action on it. That notification is the design: while it is showing, a process on
another machine can read and tap the screen, and that should never be invisible or hard to
stop.

## 3. Point a model at the host

The host speaks MCP over stdio, so any MCP client can drive the device:

```json
{
  "mcpServers": {
    "andropilot": {
      "command": "/path/to/andropilot-host/bin/andropilot-host",
      "args": ["--mcp", "--token", "the-same-token", "--skills", "/path/to/skills"]
    }
  }
}
```

`tools/list` returns the SDK's own tools — `observe`, `click`, `type_text`, `scroll_until`
and the rest — with their JSON Schemas, because `ToolCodec.toolDescriptors()` already
describes them in exactly the shape MCP wants. `tools/call` forwards the arguments to the
phone as an action document and returns the result.

One extra tool, `device_status`, is always present so a client that lists tools before the
phone has connected sees something useful rather than an empty server.

### What the host will not do

The safety policy lives on the phone and is not negotiable over the socket. A host can ask
for anything; the phone decides what it will actually do. The agent app ships with
`financialOnly()` — it asks a human about money and runs everything else unattended — and
no frame in the protocol can change that. `ToolSpec.maxRisk` is advertised in each tool's
description so a model knows what it is reaching for, but it is advice, not enforcement.

## App skills

Almost every automation failure on Android is specific to one app rather than general: a
Continue button that is an unlabelled icon, a setting two screens deep, a list that has to
be scrolled before the item exists. A model rediscovers those facts on every run and
frequently gets them wrong.

Write them down instead, keyed on the package:

```
skills/com.android.settings/SKILL.md
skills/com.whatsapp/SKILL.md
```

The host serves each file as an MCP resource, and when a result reports the package it came
from, the matching notes are appended to that result. Editing the file is enough — nothing
is compiled and the host re-reads on every access.

This is knowledge about apps, not a change to how the SDK perceives them, which is why it
lives here and not in the SDK.

## Telemetry

Optional, off by default, and not part of the SDK — `:andropilot-telemetry` is a separate
module a host opts into by adding the dependency and naming an endpoint.

```kotlin
SessionConfig(
    listeners = listOf(
        TelemetrySink.http(
            endpoint = "https://desk.local:8766/ingest",
            token = token,
            spoolDirectory = File(cacheDir, "telemetry"),
            options = TelemetryOptions(deviceId = settings.deviceId()),
        ),
    ),
)
```

Records are spooled to disk first and uploaded in gzipped batches, so a run that ends badly
— a crash, a dead battery, an app the OS killed — is still reported once the phone is back
online. Those are the runs worth looking at.

`--ingest-port` on the host accepts the batches and appends them to
`telemetry-data/events-<date>.jsonl`. That is the same format the on-device trace recorder
writes, so anything you have already written to analyse a device trace works on the
server's data unchanged.

### What is sent

The same redaction rule as a local trace file, because it is the same code: with
`includeText` off — the default — records carry roles, bounds, resource ids, flags,
outcomes, failure reasons and timings, and none of the screen's content. That withholds
more than the snapshot: a failure message quotes the text it failed to match, and a diff
summary names the labels that appeared, so those prose fields go too.

Turning `includeText` on uploads whatever was on screen, including messages and one-time
codes. It is a switch in the agent app's settings and it is off until you move it.

The transport refuses plaintext `http://` to a public host outright. A private address or
localhost is allowed, because self-hosting on a LAN is the case this was built for.

### Judging whether the model decided correctly

The records give you action-and-outcome and the before/after of the screen. They do not
give you *intent*, and correctness is intent against outcome — without it you have a crash
log. Have the agent emit its stated goal for each step as an `AgentEvent.Note`; those
travel the same path as everything else.

Worth computing from the first day: failure rate by `FailureReason`, the `STALE_ELEMENT`
rate (the reliability canary), the `AMBIGUOUS_TARGET` rate (the perception canary), steps
per completed task, and repeated identical actions against the same target, which means the
model is stuck.
