# Skills

Use two layers:

```text
skills/_global/SKILL.md                 # applies to every Android app
skills/com.android.settings/SKILL.md   # package-specific exceptions and shortcuts
skills/com.microsoft.emmx/SKILL.md     # optional Edge-specific notes
```

The global skill should describe a short, reusable operating loop: observe, identify the
foreground package, choose semantic selectors, act once, observe again, recover from the
specific failure, and verify the final state. It should not assume a particular launcher,
manufacturer, app layout, or package name.

App skills should contain only facts that are genuinely specific to that package: unusual
labels, stable resource ids, menu locations, package-specific recovery, and known device
variants. Do not repeat the global workflow in every app skill.

```
skills/_global/SKILL.md
skills/com.android.settings/SKILL.md
```

The host serves these to the model: as MCP resources, and appended to any result that
reports coming from that package. Nothing is compiled and nothing is validated; edit a file
and the next call sees it.

Write what a person would tell a colleague driving the app for the first time. Keep each
file short: skills are injected into model context and prose costs tokens on every run.
