# Catching up with the competition

What this project lacks compared with Postman, Bruno, the JetBrains HTTP Client and VS Code REST Client, and what to do about it.
Source: an external review of the repository (Nov 2026, from the public docs, PRs and review threads; the reviewer did not build or run the code).
Every claim below was checked against [features.md](features.md) and [PLAN.md](PLAN.md); gaps already planned point to their task there.
Out-of-scope decisions are in [PLAN.md](PLAN.md) ("Later / out of scope for now").

## Positioning

Not a Postman clone: **an IDE-native, Git-native client for the JetBrains `.http` format, with one execution engine behind a CLI, a desktop app and an IntelliJ plugin.**
The same `.http` file runs in the IDE and in CI (JUnit and JSON reports, exit codes). Postman wins on cloud collaboration and breadth, Bruno on GUI polish and scripting.
We do not try to match either there; we win on plain files, no proprietary collection format and the shared engine.

| Capability                                  | This project                   | Bruno  | Postman | JetBrains HTTP Client   |
|---------------------------------------------|--------------------------------|--------|---------|-------------------------|
| Plain-text, Git-friendly requests           | `.http`                        | `.bru` | partial | `.http`                 |
| CLI and CI reports                          | yes                            | yes    | yes     | yes (separate CLI)      |
| Assertions, chaining, environments, cookies | yes                            | yes    | yes     | yes                     |
| Free in an open IDE (Community)             | plugin built, not yet verified | n/a    | n/a     | paid (Ultimate)         |
| Editor intelligence                         | not yet                        | n/a    | n/a     | yes                     |
| Multipart / form bodies                     | not yet                        | yes    | yes     | yes                     |
| Pre/post-request scripts                    | no                             | yes    | yes     | yes (response handlers) |
| GraphQL, WebSocket, gRPC, OAuth 2           | no                             | yes    | yes     | yes                     |
| Postman / cURL import                       | no                             | yes    | n/a     | yes                     |
| Cloud sync, team workspaces                 | no (non-goal)                  | paid   | yes     | no                      |

## 1. Already planned (keep the order in PLAN.md)

| Gap                                                        | Task                  |
|------------------------------------------------------------|-----------------------|
| Plugin never tried in a real IDE, Community unverified     | T21 (release blocker) |
| Multipart and form bodies, file uploads                    | T8                    |
| Variable completion, diagnostics and find in the editors (the analysis in `core` is done, T13) | T16, T22 |
| Response history browser and compare (saved responses `>>` / `<>` are done, T26) | T18 |
| Assertion results in the desktop and plugin panels         | T17, T22              |
| Run all, remember environment in the plugin                | T23                   |
| Cancel button                                              | T19                   |
| Installers and plugin zip built in CI                      | T24                   |

## 2. Gaps not in the plan yet

Ordered by value. "Evidence" is where the current behavior is documented or observed.

| #  | Gap                                                                                                                                                                                                                                         | Why it matters                                                                                                                                              | Evidence                                                                     | Size |
|----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|------|
| G8 | **Cookie domain checks against the Public Suffix List** (or document the approximation prominently).                                                                                                                                        | Cookie scoping is approximate today. Low impact for a dev tool.                                                                                             | features.md, cookie jar notes                                                | S    |
| G9 | **Adoption and distribution.** Publish the plugin, desktop installers and a downloadable CLI; add examples and a getting-started page.                                                                                                      | The product is feature-rich but not installable by an outsider yet.                                                                                         | PLAN.md T24, T25                                                             | M    |

## 3. Decisions to make (currently out of scope)

These are what Bruno / Postman users will ask for. Each needs a decision, not a ticket.

| Item                                        | Competitors        | Suggested stance                                                                                                                      |
|---------------------------------------------|--------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Postman and cURL import                     | Bruno, JetBrains   | Cheapest way to win switchers; a CLI `httpc import` that writes `.http` files. Revisit after G1-G6.                                   |
| Pre/post-request scripts, response handlers | all                | Needs a sandboxed script engine (a third-party dependency; would live in `adapters`). Assertions + chaining cover most CI use. Defer. |
| OAuth 2 flows, Digest auth                  | Postman, JetBrains | Defer; Basic and Bearer cover the common case.                                                                                        |
| GraphQL, WebSocket, gRPC                    | all                | Defer. They already fail loudly: a parse error naming the line (G4, done).                                                                                               |
| OpenAPI-based completion, HTTP/2 settings   | JetBrains          | Later, after editor intelligence (T16, T22).                                                                                     |
| Cloud sync, team workspaces                 | Postman            | Non-goal: Git is the sync mechanism.                                                                                                  |

## 4. Suggested order

1. T21 first: verify the plugin in a Community IDE (T13 and T26 are merged).
2. Robustness quick-wins: G1-G7 are done, G8 can follow.
3. Planned feature-work from PLAN.md: T8, then the editor-intelligence and plugin tasks.
4. Distribution (G9, T24, T25) and the import decision.

Do not add protocols or collaboration before the plugin is verified and the quick wins land: the IDE experience is the differentiator.
