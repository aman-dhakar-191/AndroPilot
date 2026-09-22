# Play Store

Installing, updating and opening apps.

## Structure

- Home / Games / Apps / Search across the bottom or top; the search field is the reliable
  entry point. Search by app name, then open the result whose developer matches what you
  expect -- store listings for lookalike names are common.
- An app's page shows one primary button whose label is the state: Install, Open, Update,
  or a price. Read the label before tapping; it is the only way to know what will happen.

## Traps

- Install is not instant. After tapping Install the button becomes progress and then Open.
  Poll with `observe` rather than assuming, and do not tap again -- a second tap during
  download can cancel it.
- A paid app, or an in-app purchase, goes to a payment sheet. That is a financial action.
- "Open" launches the app and the foreground package changes. Prefer `launch_app` with the
  package name once installed; it is deterministic where tapping Open is not.

## Safety

- Never accept a purchase, start a subscription, or enter payment details. Report the price
  and stop.
- Do not update or uninstall anything the goal did not name. Updating AndroPilot's own apps
  from here will stop the accessibility service.
