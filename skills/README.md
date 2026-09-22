# Skills

Notes that go in front of the model, in three layers:

```text
skills/_global/SKILL.md                  # every app, every run
skills/_unknown/SKILL.md                 # any app without its own file
skills/com.whatsapp/SKILL.md             # one package
```

## How each layer reaches the model

- **`_global`** is part of the system prompt. It applies on every screen, so it is worth
  its tokens on every turn.
- **App notes are indexed in the prompt and delivered on demand.** The prompt lists which
  packages have notes; the notes themselves arrive attached to the first result that
  reports coming from that app, once per run. Holding every file in context on every turn
  would cost the whole library to help with the one app a run opens, and would get worse
  with each skill added -- which would make a growing library the thing that ruins the
  prompt.
- **`_unknown`** is delivered the first time a result comes from a package with no file.
  The uncovered app is the common case -- a phone holds a hundred apps and a dozen have
  notes -- so it must not be the case that gets no help.

Over MCP, app notes ride along on any result from that package, as before.

## Writing one

Start the file with `# Name`; that title is what the index shows next to the package.

Write what a person would tell a colleague driving the app for the first time:

- **Structure** -- how the app is laid out, so the model knows where to look.
- **Traps** -- what reliably goes wrong here. This is the most valuable section, and the
  part nothing else can supply.
- **Safety** -- what must not be touched, and which actions are irreversible or public.

Keep to what is durable. Exact labels, positions and wording change with app versions,
locales and A/B tests; structure, traps and consequences do not. A note that asserts a
button's text will one day be confidently wrong, and the model will trust it over the
screen in front of it -- which is worse than having no note at all. When something must be
read from the screen, say so rather than guessing it here.

Do not repeat the global workflow in an app file.

Nothing is compiled and nothing is validated: edit a file and the next run sees it.
