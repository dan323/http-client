# Agent skill

A skill that teaches coding agents (Claude Code and others that read `SKILL.md`) the `.http` dialect of this project and how to drive `httpc`: write requests and assertions, run `httpc FILE --check` first, read exit codes and reports. It is for people who *use* the client, not for developers of this repository.

## Claude Code

```
/plugin marketplace add dan323/http-client
/plugin install http-client@http-client
```

Or copy `skills/http-client/` into `~/.claude/skills/` (all projects) or `.claude/skills/` (one project).

## Other agents

Point the agent at [`skills/http-client/SKILL.md`](skills/http-client/SKILL.md) (or paste it into `AGENTS.md`); it is plain Markdown with a short front matter.

## Keeping it true

The skill restates behaviour documented in [`docs/features.md`](../docs/features.md). When the dialect or the CLI options change, update `SKILL.md` in the same change. It describes what exists only: no MCP server yet (T34).
