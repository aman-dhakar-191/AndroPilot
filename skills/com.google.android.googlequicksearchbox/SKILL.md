# Google (search and Assistant)

## Structure

- A search field over a discover feed. Results are a scrollable page of cards and links.
- This package also backs the launcher search on some devices, so you can land here without
  having "opened" an app.

## Traps

- Results open a browser or another app, which changes the foreground package. Observe
  after tapping a result rather than assuming you are still in Google.
- Voice and Lens entry points sit inside the search field and start capture immediately.
  Do not tap them.

## Safety

- Searching is read-only and safe. Do not sign in or change account settings.
