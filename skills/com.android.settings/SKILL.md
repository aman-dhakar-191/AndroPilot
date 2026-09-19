# Android Settings

An example, and a real one — these are the things an agent gets wrong on the Settings app.

- Wi-Fi is under **Network & internet**, not at the top level, on most builds. The label
  differs by manufacturer ("Connections" on Samsung), so prefer `scroll_until` with a text
  selector over assuming a position.
- The search box at the top is usually faster and more reliable than navigating: launch
  Settings, click the search field, type the setting's name.
- Toggle rows report as two elements, the row and the switch. Clicking the row usually
  works; clicking the switch is what actually changes the value when the row only opens a
  detail screen. If a click reports `no change`, try the other one.
- Many screens are longer than they look. An element that is not found may simply be below
  the fold: `scroll_until` before concluding it is absent.
