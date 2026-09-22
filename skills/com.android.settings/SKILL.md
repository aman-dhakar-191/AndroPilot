# Android Settings

An example, and a real one — these are the things an agent gets wrong on the Settings app.

- Wi-Fi is under **Network & internet**, not at the top level, on most builds. The label
  differs by manufacturer ("Connections" on Samsung), so prefer `scroll_until` with a text
  selector over assuming a position.
- Do Not Disturb is usually under **Notifications > Do Not Disturb**, but manufacturers may
  label it **Modes**, **Sound & vibration**, or **Notifications and status bar**. Prefer the
  Settings search box with "Do Not Disturb" or "DND" over assuming the category path.
- The search box at the top is usually faster and more reliable than navigating: launch
  Settings, click the search field, type the setting's name.
- If the search row is already visible after launching Settings, click it immediately. Do not
  call `scroll_until` to find an element that is already present: some Settings builds move
  the header off-screen and the next action then targets stale content. If a fuzzy text selector
  is ambiguous, narrow it with the visible bounds or an element index; do not keep retrying the
  same selector after the screen changes.
- Toggle rows report as two elements, the row and the switch. Clicking the row usually
  works; clicking the switch is what actually changes the value when the row only opens a
  detail screen. If a click reports `no change`, try the other one.
- DND may show a schedule or an exceptions/details screen before the master switch. Confirm
  the current state from the master **Do Not Disturb** switch, and use the row or switch
  element that actually changes it rather than changing a schedule by accident.
- If Settings search or navigation does not respond, return Home and open Quick Settings by
  swiping down from the top edge. Find the **Do Not Disturb** or **Modes** tile, swipe across
  tile pages if needed, toggle it, and observe again to verify that the tile reports enabled.
- For the notification shade, prefer the global `press_key` action with `notifications`, then
  observe the `com.android.systemui` screen. For the expanded POCO control centre, use
  `press_key` with `quick_settings`; report tile state without clicking a tile unless the goal
  explicitly asks for a change. Use `back` to close either panel and return to the prior app.
- Many screens are longer than they look. An element that is not found may simply be below
  the fold: `scroll_until` before concluding it is absent.
