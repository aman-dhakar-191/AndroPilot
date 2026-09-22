# Any app without specific notes

You are in an app nobody wrote notes for. That is the normal case, not a problem: most
phones hold a hundred apps and a dozen have notes. Treat the screen as the source of truth
and work from Android's conventions, which almost every app follows.

## Read the screen before assuming a shape

- `observe` first. Name the foreground package and what kind of screen this is -- a list, a
  form, a detail page, a player, a dialog -- before deciding anything.
- Most apps are a bottom navigation bar of 3-5 tabs, or a top-left hamburger, over a list
  that opens detail pages. Find which of those this is; it tells you where things live.
- A screen that looks empty is usually still loading. Observe again before concluding the
  app is broken or the content is absent.

## Finding things

- Look for the target on the current screen first. Scroll only when it is genuinely not
  visible, and observe after each scroll -- ids and bounds belong to one snapshot.
- Search is the fallback when the target is not reachable by looking, not the first move.
  A magnifier icon or a "Search" field is nearly always top of the screen or in the tab bar.
- Unlabelled icons are normal. Use role, resource id, position, and what is next to them.
  If two things match equally, say so rather than picking -- that is what `ambiguous_target`
  is telling you.

## When a launch looks like it failed

- A timeout from `launch_app` usually means the app is opening, not that it is absent.
  Observe first. A splash screen, a permission dialog, or a first-run page is the common
  thing sitting in front of the app you asked for.
- Deal with the prompt, then continue. Do not go looking for the app again.

## Getting out

- `press_key back` closes dialogs, sheets, and search. It is safe and it is the way out of
  almost every dead end.
- Back from the app's first screen leaves the app. That is fine, but say so, because the
  next observation will be a different package than you expect.
- An unexpected first-run screen -- login, permissions, "what's new", a rating prompt -- is
  the common reason a plan stops working. Read it and dismiss it deliberately; do not tap
  through it blind.

## What not to do

- Do not sign in, accept terms, grant permissions, or create an account unless the goal
  asked for exactly that. Stop and report instead.
- Do not buy, send, post, delete, or share. If the goal needs one of those and the device
  asks a human to approve it, that is the safety policy working -- report it and stop.
- Do not tap something because it is probably right. Say what is ambiguous and stop.

## Finishing

- Verify from a fresh `observe` that the end state is what was asked for, and report what
  is actually on screen -- separately from anything you inferred.
- If you could not do it, say which step failed and what the screen showed. A precise
  failure is worth more than a plausible guess.
