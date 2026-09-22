# Security (Xiaomi)

Permissions, battery, autostart and app management on HyperOS/MIUI devices.

## Why it matters here

This is where an OEM hides the settings that decide whether background apps keep running.
An agent app that loses its socket overnight, or an accessibility service that dies in
Doze, usually traces back to a toggle on one of these screens.

## Structure

- Sections for permissions, battery/performance, and app management, each opening its own
  list. Names move between HyperOS versions -- find the section by reading the screen, not
  by a remembered position.
- Per-app screens are reached by picking the app from a list, which is usually alphabetical
  and long. Use the in-screen search when there is one.

## Traps

- Several toggles look identical and mean different things (autostart vs background
  activity vs battery saver exemption). Read the label next to the switch you are about to
  touch, and target the switch, not the row's decorative text.
- Some changes show a confirmation dialog that defaults to the cancelling option.

## Safety

- Do not remove permissions or restrict apps the goal did not name. Revoking accessibility
  or background permission from the agent app ends the run and cannot be undone from here.
