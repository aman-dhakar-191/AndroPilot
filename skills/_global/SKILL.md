# Android UI Operation

Use this workflow for every Android app. App-specific skills are hints, not substitutes for observing the current screen.

## Before Acting

- Start with `observe` unless the task explicitly needs a global action first.
- Identify the foreground package and title. Use `list_apps` when you need an app label-to-package mapping; do not guess package names.
- Prefer semantic selectors using visible text, role, resource id, and clickable state. Use coordinates only when the screen has no usable semantics.
- After every navigation or global action, observe again. UI element ids and bounds are only valid for the snapshot that produced them.

## Navigating

- Use `launch_app` with the exact package from `list_apps`, then wait for the foreground package.
- Search within an app when a search field exists instead of guessing menu paths.
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
- `app_unavailable`: call `list_apps` and use the exact package name.
- Verify the final state from a fresh observation. Report what is visible and distinguish confirmed state from inference.
- When the task is read-only, leave the app and device state unchanged and return to the prior app when requested.
