# Catching up with the competition

What this project lacks compared with Postman, Bruno, the JetBrains HTTP Client and VS Code REST Client, and what to do about it.
Source: an external review of the repository (Nov 2026, from the public docs, PRs and review threads; the reviewer did not build or run the code).
Every claim below was checked against [features.md](features.md) and [PLAN.md](PLAN.md); gaps already planned point to their task there.
Out-of-scope decisions are in [PLAN.md](PLAN.md) ("Later / out of scope for now").

## Positioning

Not a Postman clone: **an IDE-native, Git-native client for the JetBrains `.http` format, with one execution engine behind a CLI, a desktop app and an IntelliJ plugin.**
The same `.http` file runs in the IDE and in CI (JUnit and JSON reports, exit codes). Postman wins on cloud collaboration and breadth, Bruno on GUI polish and scripting.
We do not try to match either there; we win on plain files, no proprietary collection format and the shared engine.

| Capability                                  | This project                                   | Bruno  | Postman | JetBrains HTTP Client   |
|---------------------------------------------|------------------------------------------------|--------|---------|-------------------------|
| Plain-text, Git-friendly requests           | `.http`                                        | `.bru` | partial | `.http`                 |
| CLI and CI reports                          | yes                                            | yes    | yes     | yes (separate CLI)      |
| Assertions, chaining, environments, cookies | yes                                            | yes    | yes     | yes                     |
| Free in an open IDE (Community)             | plugin works, Community unverified             | n/a    | n/a     | paid (Ultimate)         |
| Editor intelligence                         | desktop and plugin (plugin unverified by hand) | n/a    | n/a     | yes                     |
| Multipart / form bodies                     | multipart yes, urlencoded as text              | yes    | yes     | yes                     |
| Pre/post-request scripts                    | no                                             | yes    | yes     | yes (response handlers) |
| GraphQL, WebSocket, gRPC, OAuth 2           | no                                             | yes    | yes     | yes                     |
| Postman / cURL import                       | no                                             | yes    | n/a     | yes                     |
| Cloud sync, team workspaces                 | no (non-goal)                                  | paid   | yes     | no                      |

## 1. Already planned (keep the order in PLAN.md)

| Gap                                                                                               | Task       |
|---------------------------------------------------------------------------------------------------|------------|
| Plugin checked on Ultimate 2026.3 only; Community and the 2026.1 baseline unverified              | T21 (rest) |
| Plugin editor intelligence, run all and linked responses are implemented but unverified in an IDE | T21 (rest) |
| Installers and plugin zip built in CI (release workflow done; T25 QA of the assets remains)       | T25        |

## 2. Gaps not in the plan yet

All of these gaps (G1-G9) are done; the details are in the changelog and [features.md](features.md).

## 3. Decisions to make (currently out of scope)

These are what Bruno / Postman users will ask for. Each needs a decision, not a ticket.

| Item                                        | Competitors        | Suggested stance                                                                                                                                                                                            |
|---------------------------------------------|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Postman and cURL import                     | Bruno, JetBrains   | Cheapest way to win switchers; a CLI `httpc import` that writes `.http` files. Bruno collections convert with `httpc convert-bruno` (T35, running them directly is open); Postman and cURL stay a decision. |
| Pre/post-request scripts, response handlers | all                | Needs a sandboxed script engine (a third-party dependency; would live in `adapters`). Assertions + chaining cover most CI use. Defer.                                                                       |
| OAuth 2 flows, Digest auth                  | Postman, JetBrains | Defer; Basic and Bearer cover the common case.                                                                                                                                                              |
| GraphQL, WebSocket, gRPC                    | all                | Defer. They already fail loudly: a parse error naming the line (G4, done).                                                                                                                                  |
| OpenAPI-based completion, HTTP/2 settings   | JetBrains          | Later; editor intelligence (T16, T22) is done.                                                                                                                                                              |
| Cloud sync, team workspaces                 | Postman            | Non-goal: Git is the sync mechanism.                                                                                                                                                                        |

## 4. Suggested order

1. T21 (rest): verify the plugin in a Community IDE and on the 2026.1 baseline (checked by hand on Ultimate 2026.3).
2. Robustness quick-wins: G1-G8 are done.
3. Planned feature-work from PLAN.md: Bruno import (T35), the MCP server (T34), the language server and VS Code (T36, T37) and the guide tasks (T28-T31).
4. Publishing to external channels (Marketplace, Homebrew, Scoop, winget; see the follow-ups in [getting-started.md](getting-started.md)), T25 and the import decision. In-repo distribution (G9: release workflow, CLI distribution, getting-started page, examples) is done.

Do not add protocols or collaboration before the plugin is verified and the quick wins land: the IDE experience is the differentiator.
