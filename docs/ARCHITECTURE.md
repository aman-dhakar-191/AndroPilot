# Architecture

This document explains why AndroPilot is shaped the way it is. Most of the decisions are
forced by Android platform constraints, so those come first.

## 1. Platform constraints that drove the design

**There is exactly one sanctioned way to read and drive another app's UI.** On unrooted,
production Android that is `AccessibilityService`. The alternatives were evaluated and
rejected:

| Option | Why not |
|---|---|
| `uiautomator` / `UiDevice` | Instrumentation-only. Cannot run in a shipped app. |
| ADB / shell bridge | Requires a host machine or a persistent debug bridge. Not a product. |
| Root, `input` injection | Not available on user devices; would be a security regression. |
| `MediaProjection` + vision only | Gives pixels but no semantics, no element identity, no reliable input target. |

`AccessibilityService` is also the *right* choice on its own merits: it is granted by the
user, revocable, visible in system settings, and honours `FLAG_SECURE`. The SDK is a highly
privileged component and should sit behind a permission the user understands and controls.

**The accessibility tree is not a reliable source of truth.** Real apps ship unlabelled
`ImageButton`s, custom views with meaningless class names, `WebView`s with flattened
structure, and screens with no accessibility support at all. Any design that assumes a clean
tree will work on the developer's own app and fail everywhere else. Hence: roles derived
from capability flags as well as class names, `UNKNOWN` as a legitimate answer, and a
pluggable visual fallback.

**Node handles go stale, constantly.** `AccessibilityNodeInfo` references are invalidated by
recomposition, scrolling, and window changes. Holding one across an await is a bug. Hence:
elements are immutable value objects with *path-based ids*, and the driver re-resolves the
path against the live tree at dispatch time. If the path no longer resolves, the action fails
as `STALE_ELEMENT` rather than landing on whatever now occupies that position.

**Gestures fail in specific, reproducible ways.** A swipe starting in the navigation bar is
taken by the system; one starting at a screen edge triggers back-navigation; one shorter than
touch slop is dropped. Hence a `GesturePlanner` that encodes those rules as pure arithmetic
and is unit-tested against synthetic geometry.

**Screenshots are API 30+ and can be refused.** `takeScreenshot` needs Android 11 and returns
failure on secure screens. Hence: screenshots are optional, vision is optional, and both
report `UNSUPPORTED` / `ACTION_REJECTED` explicitly instead of degrading silently.

**Timing is not knowable in advance.** Animations, network-backed loading and IME transitions
mean a fixed sleep is either flaky or slow, and it never adapts to the device. Hence: settle
detection by structural signature, not by clock.

## 2. The central split

```
                 ┌──────────────────────────────────────────┐
  AI agent ──────▶  AndroPilotSession  (execute(AgentAction) │
  (any runtime)  │      ↓                → ActionResult)     │
                 │  matching · verification · retry ·        │   andropilot-core
                 │  safety · tracing · vision fusion         │   pure Kotlin/JVM
                 │      ↓                                    │   NO Android
                 │  ┌────────────────────────────────────┐   │
                 │  │          UiDriver (interface)      │   │
                 └──┴────────────────────────────────────┴───┘
                               ↑                    ↑
              AccessibilityUiDriver          FakeUiDriver
              (andropilot-android)           (core, shipped publicly)
                               ↑
              AccessibilityService, gestures, screenshots
```

Everything that decides *whether automation is correct* is above the `UiDriver` line and has
no Android dependency. That is the single most consequential decision in the project, and it
buys three things:

1. **Testability.** 134 unit tests covering matching, ambiguity, gesture geometry, change
   detection, retry, fallback and safety run in seconds on any JVM with no emulator.
2. **A real extension point.** A remote-control transport, a second platform, or a record/
   replay harness is a new `UiDriver` implementation, not a fork.
3. **Honest module boundaries.** Nothing above the line can accidentally reach for a
   platform API and become untestable.

## 3. Perception model

`UiSnapshot` is a **flat list** of `UiElement` plus parent/child id links, not a nested tree.
Flat serializes compactly, filters cheaply, and lets an agent reference any element by id
without walking a structure. The tree is still navigable via `childrenOf` / `ancestorsOf`.

Three properties matter for an AI consumer:

- **Normalized.** Android class names are replaced by a coarse `ElementRole`. Agents reason
  far better over `BUTTON` than over `androidx.appcompat.widget.AppCompatButton`, and roles
  stay stable when an app swaps widget implementations. `className` is still there for
  callers who need it.
- **Compact.** `toCompactText()` drops uninformative containers and caps output. A raw
  hierarchy dump is mostly layout scaffolding and would dominate a model's context window for
  no benefit. The Android builder additionally drops leaf nodes that are invisible,
  zero-sized, and carry neither text nor behaviour.
- **Comparable.** `structuralSignature()` covers package, dialog/keyboard state, and the
  role + label + coarse position of every *interactive* element. Non-interactive churn — a
  ticking clock, a progress percentage — deliberately does not affect it, which is what makes
  settle detection terminate.

## 4. Why verification is a first-class concern

A silent no-op is the most common way an agent loop gets stuck: the model taps, assumes it
worked, and plans the next three steps against a screen that never changed. So every
interaction is followed by settle-and-diff, and a dispatched action that produced no
observable change is reported as `NO_EFFECT` — a *failure*, by default.

`UiDiff` matches elements across snapshots on the stable parts of their identity (resource id,
then role + label), never on ids alone, because a recomposed tree renumbers everything. It
also detects whole-list movement by finding a dominant vertical offset, which distinguishes
"the list scrolled" from "the content was replaced".

`treatNoEffectAsFailure` can be turned off for apps that legitimately show no feedback.

## 5. Matching

Selectors are declarative, serializable values. An agent emits a description of what it wants;
the SDK resolves it against whatever is on screen at that instant. That indirection is what
keeps the agent away from both coordinates and stale handles.

Rules worth knowing:

- **Boolean constraints are hard filters; text is soft.** An agent asking for `editable = true`
  never wants a non-editable element, however well the text matches. Text is where real-world
  drift lives, so it contributes a score.
- **Ambiguity is reported, not guessed.** Two equally good matches produce `AMBIGUOUS_TARGET`
  with both candidates, plus a recommendation naming the disambiguators (`index`, `region`,
  `within`, `near`). Silently picking one is how automation taps the wrong "Delete".
- **A perfect match is never ambiguous against an imperfect one.** Sibling list rows differing
  by a single character sit inside the normal decisive margin, because the same character-level
  tolerance that rescues OCR typos also blurs near-identical labels. Score alone cannot serve
  both; exactness breaks the tie.
- **Non-actionable elements are penalized, not filtered.** Containers routinely duplicate their
  child's text, so an actionable element must win decisively — but keeping the disabled or
  non-clickable match available lets the session say *"that button is disabled"* instead of
  *"nothing matched"*.

Text scoring is deliberately dependency-free and deterministic. "Semantic" here means robust
to how UIs render text (case, `&` vs `and`, ellipses, punctuation), not learned meaning.
Anything genuinely semantic is the agent's job — it can always issue a different selector.

## 6. Visual fallback

`VisionProvider` is a single-method interface returning boxes with optional text and a
confidence. Every plausible backend can produce that much: local OCR, cloud OCR, an on-device
model, a remote VLM, a bespoke template matcher. The SDK ships **no** implementation and
depends on no vision library, because which stack is appropriate (latency vs accuracy vs
privacy) is a host decision.

`PerceptionFusion` does two things, in priority order: **enrich** a semantic node that exists
but has no label (the unlabelled icon case), then **add** detections that overlap nothing
semantic. Semantic information always wins on conflict — it comes from the app itself and is
not a guess. Elements that exist only visually are marked `PerceptionSource.VISUAL`, are
reachable by coordinate tap only, and cause a warning on the snapshot so an agent knows
verification for them is weaker.

## 7. Safety

Risk is classified (`READ_ONLY` → `NAVIGATION` → `MUTATING` → `SENSITIVE`) *before* the action
touches the device, with the current snapshot and the resolved target in hand — so a policy can
key off what is actually on screen ("this button says Pay £240"), not just the request.

The SDK classifies; the host decides. `SafetyPolicy` is a single-method interface. The default
implementation ranks its signals by how much they can be trusted:

1. **Structural** -- a password field, or text the caller marked `sensitive`. These come from
   the app or the caller rather than from guessing, so they are decisive.
2. **Irreversible keywords** -- "pay", "delete", "withdraw", "send". Rarely anything but the
   real thing, so they escalate on their own.
3. **Contextual keywords** -- "submit", "apply", "allow", "remove". These escalate *only* when
   the target sits inside a dialog.

The third tier is the load-bearing one. The obvious design is to flag every alarming-sounding
word, on the theory that over-asking errs safe. It does not: a policy that interrupts every
form submission teaches the user to approve reflexively, and the prompt that mattered is the
one they then wave through. Confirmation is a budget, not a free action. "Submit" on a form is
a form; "Submit" inside a modal is the commit step of something the app itself thought worth
interrupting for — and the dialog is a signal the *app* produced, not one the SDK invented.

Ancestry decides tier 3, not the snapshot's `hasDialog` flag: a dialog can be open while the
agent acts on something behind it, and only the element's position in the tree says which side
of that it is on.

Confirmations are **single-use**: an approval is keyed to the action and target and removed on
redemption, so an agent cannot obtain one "yes" and then send a hundred messages.

Nothing in the SDK is designed to bypass Android or app security. Intents are restricted to a
host-configured allow-list, because an unconstrained intent surface would be an escalation path
out of the SDK's boundaries.

## 8. Concurrency and lifecycle

- One process-wide session. The screen is a single shared mutable resource; independent
  sessions would let two callers interleave gestures with no way to reason about the outcome.
- A `Mutex` serializes action execution inside the session.
- Accessibility callbacks arrive on the main thread; traversal hops to `Dispatchers.Default`.
- The service is discovered, not constructed — the system owns its lifecycle. A disabled
  service degrades to `PERMISSION_REQUIRED` on every action rather than crashing, so a host
  can build its UI against a session before the user grants anything.

## 9. Observability

`ActionTrace` is a bounded in-memory ring answering the questions an integrator actually has:
what was attempted, in which app, how the target was located, whether semantic or visual
interaction was used, whether the UI changed, why it failed, and how long it took.

Redaction is **opt-out, not opt-in**. A component that can read every screen is one careless
log line from leaking a password or a 2FA code, so screen text and typed values are replaced
with length placeholders unless a host explicitly enables them.

## 10. Deliberate non-goals

Extension points exist for these; none is implemented, to keep the MVP honest:

- Action planning, agent memory, screen semantic understanding — these are the *agent's* job.
- Multi-device and remote control — a new `UiDriver`, when there is a real use case.
- Record/replay — the `snapshots` and `results` flows are the natural hook.
- A bundled OCR or vision model — `VisionProvider` is the seam.
