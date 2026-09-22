# Android UI Operation

Use this workflow for every Android app. App-specific skills are hints, not substitutes for observing the current screen.

## The loop

Observe -> understand the state -> choose one action -> execute -> observe again -> verify.
Every step of a task goes through it. Skipping the second observation is how a run ends up
acting on a screen that is no longer there.

## Before Acting

- Start with `observe` unless the task explicitly needs a global action first.
- Identify the foreground package and title. Use `list_apps` when you need an app label-to-package mapping; do not guess package names.
- Prefer semantic selectors using visible text, role, resource id, and clickable state. Use coordinates only when the screen has no usable semantics.
- After every navigation or global action, observe again. UI element ids and bounds are only valid for the snapshot that produced them.

## Launching an app

- `launch_app` reporting a timeout does **not** mean the app failed to open. The phone was
  busy, which is what launching an app looks like. Call `observe` and read the screen
  before deciding anything.
- What is usually in front of you after a "failed" launch: a splash screen, a permission
  dialog, a notification prompt, a what's-new page, or a login. The app has launched; a
  prompt is on top of it. Deal with the prompt and carry on.
- Do not conclude an app is missing because its usual screen is not visible yet.
- A launch is confirmed by the foreground package in a fresh `observe`, not by the tool
  response. Treat that observation as the moment the current app changed.

## Knowing what is installed

- `list_apps` once, when you first need a package name. The result stays true for the whole
  conversation -- installed apps are the most stable thing about a device.
- The host will not read the list twice. Ask again and it tells you to reuse the earlier
  result, which is still in this conversation; scroll back to it.
- `app_unavailable` is the one answer that means the list was wrong. The host re-reads it
  for you after that, so `list_apps` at that point is worth calling again.
- Listing apps is never the right response to a timeout, an `element_not_found`, or a
  screen that looks wrong. Those are all answered by observing.

## Navigating

- Use `launch_app` with the exact package from `list_apps`, then wait for the foreground package.
- After observing, first check whether the requested target is already visible. Open the
	visible matching row directly, using its specific resource id, role, label, or nearby
	parent. Do not open an app search screen for a target that is already on the current page.
- Use in-app search only when the target is not visible, below the fold after a reasonable
	scroll, or not identifiable from the current screen. Search is a fallback, not the default.
- Scroll only when the target is not visible. If a selector fails, observe before retrying; do not repeat a stale selector.
- When multiple elements match, use role, resource id, index, region, or a nearby parent to disambiguate. Never choose randomly.
- Use `press_key` with `back` to close dialogs, notification shade, and Quick Settings. Use `notifications` for the notification shade and `quick_settings` for the expanded control centre.

## Acting Safely

- Read the current state before changing a setting. For switches and tiles, target the switch or tile itself, not a decorative label.
- Do not toggle, dismiss, submit, delete, send, or install unless the goal asks for that change.
- For text entry, confirm the intended field and replace or append deliberately. Treat credentials and one-time codes as sensitive.
- Prefer one action at a time, then observe or verify the result. If the UI did not change, check whether the action was dispatched to the right element before retrying.

## Recovery and Verification

- `element_not_found`: observe, then search or scroll in the correct container.
- `ambiguous_target`: refine the selector; do not use the first candidate by default.
- `stale_element`: observe and resolve the target again.
- `no_effect`: confirm the action's intended state and target; some navigation may require waiting for a window change.
- `timeout`: the device was busy, not absent. Observe and read the screen; the action may
  well have happened.
- `app_unavailable`: the package is not installed. This is the case where re-reading the
  app list is right.
- Verify the final state from a fresh observation. Report what is visible and distinguish confirmed state from inference.
- When the task is read-only, leave the app and device state unchanged and return to the prior app when requested.
