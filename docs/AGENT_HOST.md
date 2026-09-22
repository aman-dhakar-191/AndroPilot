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

```powershell
.\scripts\run-host.ps1              # Windows
```
```bash
./scripts/run-host.sh                # macOS, Linux
```

That builds if it needs to, then prints the two things the phone needs:

```
  Host endpoint for the agent app:  ws://192.168.1.12:8765/agent
  Shared token:                     kQ7vN2pX...
```

**Settings live in `~/.andropilot-host/settings.json`**, so nothing has to be typed twice:

```json
{
  "bind": "0.0.0.0",
  "port": 8765,
  "token": "the shared secret the phone presents",
  "ingestPort": 8766,
  "uiPort": 8080,
  "modelEndpoint": "http://localhost:20128/v1",
  "modelId": "a model id, or a gateway combo name",
  "apiKey": "the api key"
}
```

### Picking a model

The control page reads `GET {modelEndpoint}/models` and offers what comes back as a
type-to-search list, with the host's configured `modelId` pre-filled. Pick one there and it
is used for that run only; the settings file is unchanged. The host also prints the list at
startup and warns when `modelId` is not in it, because a gateway that rejects a name
usually says only that it did, not what would have worked. An endpoint with no `/models` is
fine -- the box still accepts a name typed by hand, sent through untouched.

**OmniRoute combos.** A persisted combo (Settings -> Combos) is matched on its *exact*
name, with no fuzzy matching, and `combo/<name>` is the unambiguous spelling. `auto` and
`auto/*` are a different mechanism that builds their own candidate pool and deliberately do
**not** use your combos. So an error like

```
Unable to determine provider for model 'AndroPilot'. Use a provider/model prefix
(e.g. openai/AndroPilot) or ensure the model is added as a combo entry.
```

means the gateway did not recognise that string -- try `combo/AndroPilot`, and check the
name against the list the page now shows. The `openai/` in that message is OmniRoute's own
example text; the host never adds a prefix to what you configured.

`modelId` is whatever string your gateway answers to. It does not have to be a model:
an OmniRoute combo is addressed by its own name (`"AndroPilot"`, say) and picks a model
behind it, so a fallback chain configured there is invisible to the host, which only ever
sends the string. Older files that spell these fields `model` and `modelKey` are still
read, so nothing breaks by not renaming them.

Every field is optional, and anything on the command line overrides the file — a settings
file you could not override for one run would be worse than typing the flags. A malformed
file is refused rather than ignored: falling back to defaults would start on a different
port with a freshly invented token, and the only symptom would be a phone that no longer
connects.

It is in the home directory rather than beside the checkout because the token is in it, and
a token in the working copy is lost the moment the repository is moved or re-cloned. The
file is created owner-only; it holds a token and, if you put one there, a model key.
`ANDROPILOT_MODEL_KEY` still wins over the file.

**The token is kept, not regenerated.** Started by hand the host invents a random one every
launch, which quietly invalidates the endpoint saved on the phone and makes every restart a
reconfiguration. The script stores one in `.andropilot/token` (git-ignored) and reuses it,
so the phone is set up once and reconnects on its own after that.

It binds `0.0.0.0`, because a phone cannot reach a loopback socket. **The token is the only
thing between that port and everyone else on the network** -- which is why it has to be a
real one, and why it is not printed anywhere it would be logged.

Useful switches:

| | |
|---|---|
| `-NoBuild` / `NO_BUILD=1` | skip Gradle when nothing changed |
| `-UiPort 8080` / `UI_PORT=8080` | also serve the control page (loopback only; needs the control-page change merged, otherwise the host rejects the flag) |
| `-Mcp` / `MCP=1` | serve MCP on stdio instead |
| `-ModelEndpoint ... -Model ...` | add the agent loop; see below |

To do it by hand instead:

```bash
./gradlew :andropilot-host:installDist
./host/build/install/andropilot-host/bin/andropilot-host \
    --port 8765 \
    --token "$(openssl rand -base64 24)" \
    --skills ./skills \
    --ingest-port 8766
```

By hand, everything binds to `127.0.0.1` unless you pass `--bind`; widen that deliberately,
because a socket into this process can drive a phone.

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

There are two ways, and they coexist. Which you want depends on where the model lives.

### Your own model endpoint (the host drives)

Anything that speaks the OpenAI chat-completions shape works, whether it is on your machine
or not. **All three of endpoint, key and model name go here, on the host — none of them
belong in the app.** The phone is only ever told two things: where the host is, and the
shared token.

A local gateway or router:

```bash
export ANDROPILOT_MODEL_KEY=...       # not --model-key: an argument is in the process list
./host/build/install/andropilot-host/bin/andropilot-host \
    --token "$TOKEN" --skills ./skills \
    --model-endpoint http://localhost:4000/v1 \
    --model your-model-name \
    --goal "turn on wi-fi"
```

With the script, the endpoint and model are switches and the key stays in the environment:

```powershell
$env:ANDROPILOT_MODEL_KEY = "sk-or-v1-..."
.\scripts\run-host.ps1 -ModelEndpoint https://openrouter.ai/api/v1 -Model vendor/model-name -UiPort 8080
```

The key is never passed as an argument, by either script: an argument is readable by
anything that can list processes, and the host reads the variable from the environment it
inherits.

By hand, OpenRouter is the same shape as a local gateway with a different base URL and
namespaced model names:

```bash
export ANDROPILOT_MODEL_KEY=sk-or-v1-...
./host/build/install/andropilot-host/bin/andropilot-host \
    --token "$TOKEN" --skills ./skills \
    --model-endpoint https://openrouter.ai/api/v1 \
    --model vendor/model-name \
    --goal "turn on wi-fi"
```

Give the base URL ending in `/v1`; the client appends `/chat/completions` itself. Ollama and
llama.cpp expose the same shape, so a genuinely local model is the same command with a
different URL.

Leave `--goal` off for an interactive prompt: type a task, watch it run, type another,
without dropping the connection to the phone.

### Or watch it in a browser

```bash
andropilot-host --token "$TOKEN" --skills ./skills \
    --model-endpoint https://openrouter.ai/api/v1 --model vendor/model-name \
    --ui-port 8080
```

`http://127.0.0.1:8080` gives you a goal box, the model's reasoning and every action as
they happen, and a Stop button. It is the only place you can watch a run: while one is in
progress the model is driving other apps, so the phone's own screen is not available to
look at.

**The page is always bound to loopback, whatever `--bind` says.** `--bind` exists so a
phone on the LAN can reach the agent socket. It must not also put a start-a-run button on
the network, and the page carries no password precisely because nothing but this machine
can reach it -- those two decisions stay joined.

Stop asks the run to end after the step in flight. An action already sent to the phone is
going to happen; nothing further is asked of the model or the device.

**Where the model runs is a privacy decision, and the SDK cannot make it for you.** A hosted
router means the screens the agent observes are sent to whoever it routes to. That is fine
for turning Wi-Fi on and a poor idea for anything with a balance or a message on it. The
redaction rules in this project govern what reaches *logs and telemetry*; they have no
bearing on what you deliberately hand to a model, because the model cannot act on a screen
it has not been shown.

The loop asks your endpoint what to do, runs the tool calls it comes back with, feeds the
results in, and repeats until the model replies with plain text and no tool call. It stops
at `--max-steps` (40 by default) regardless — a model that has misread a screen will keep
trying, and this is somebody's actual phone.

Nothing about the model is compiled in. Endpoint, key and model name are configuration, the
same as the phone's endpoint, and the code path behind them is a `ModelClient` interface
with one implementation, so a differently-shaped backend is another adapter rather than a
rewrite.

### An MCP client (the client drives)

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

### Answering on the phone

The agent app ships `financialOnly()`: anything the policy reads as financial stops and
waits for a person. The phone is where that is answered -- the agent screen lists whatever
is pending with Approve and Refuse, and until you answer, the run does not continue.

Approving **re-runs** the action against whatever is on screen at that moment, not the
screen the agent saw when it asked. A human takes seconds and screens move, so re-resolving
the selector is the only honest option.

The same screen keeps a short list of what the agent has been doing, for looking at
afterwards. It cannot be a live view: while a run is in progress the model is driving other
apps and this screen is not on top. The browser page is where you watch a run.

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

The records give you action-and-outcome, the before/after of the screen, **and intent** —
the last one only when the host's own loop is driving.

Intent is the piece that makes a run reviewable rather than merely logged. An action cannot
tell you afterwards whether a tap was the right one or a guess; only the model's stated
reason can. So each step's reasoning travels down to the phone and is emitted as an
`AgentEvent.Note` (`kind=intent`, with the closing message as `kind=conclusion`), landing in
the same ordered stream as the result it produced. Recording it on the device rather than
host-side is what lines the two up.

Driving through MCP instead, the client owns the model and its reasoning never reaches the
host, so those notes are absent and you are back to action-and-outcome.

Worth computing from the first day: failure rate by `FailureReason`, the `STALE_ELEMENT`
rate (the reliability canary), the `AMBIGUOUS_TARGET` rate (the perception canary), steps
per completed task, and repeated identical actions against the same target, which means the
model is stuck.
