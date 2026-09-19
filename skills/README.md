# App skills

One directory per Android package, each with a `SKILL.md`:

```
skills/com.android.settings/SKILL.md
```

The host serves these to the model: as MCP resources, and appended to any result that
reports coming from that package. Nothing is compiled and nothing is validated; edit a file
and the next call sees it.

Write what a person would tell a colleague driving the app for the first time — where
things live, what is unlabelled, what has to be scrolled to. Keep it short. It is injected
into a model's context on every relevant turn, so a page of prose costs something on every
step.
