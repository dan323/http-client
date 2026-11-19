# Plan

What is still missing, the tasks that deliver it, and the order to do them in.
Current state: see [features.md](features.md); what differs from the JetBrains HTTP Client: [jetbrains-compatibility.md](jetbrains-compatibility.md). History: see [../CHANGELOG.md](../CHANGELOG.md).
Review fixes that preceded this plan (R1-R13, all done): see [../CHANGELOG.md](../CHANGELOG.md); what was accepted is in [known-limitations.md](known-limitations.md).

## 1. Features

Status: **done**, **partial** (what is missing is in the task table) or **open**.

| ID | Feature | Area | Status |
|---|---|---|---|
| F1 | Response assertions (`# @assert ...`) and a test-runner mode with pass/fail results | core, CLI, UIs | done (the plugin panel is unverified by hand, see T21) |
| F2 | File variables (`@host = localhost`) and request settings (`# @timeout`, `# @no-redirect`, `# @no-cookie-jar`, `# @proxy`) | core | done |
| F3 | Auth helpers (Basic encoding; Bearer needs none) | core | done |
| F4 | Multipart and form bodies, file uploads | core | done: multipart parts and `< path` file parts; urlencoded bodies stay plain text |
| F5 | Cookie jar between requests | core | done |
| F6 | More dynamic variables (`$random.*`, `$env.X`, timestamp offsets, `$randomInt` range) | core | done |
| F7 | More extraction: XPath, regex, headers (for chaining and assertions) | core | done |
| F8 | Executor settings: redirects, timeouts, proxy | core | done |
| F9 | Response history and saved responses | core, UIs | partial: `>>` / `>>!` / `<>`, link recording (T26) and the desktop history browser (T18) done; opening a `<>`-linked response is done in the desktop app (T27); the plugin part (T27) is open |
| F10 | CLI: `--json`, `--junit`, `--quiet`, `--output`, `--var`, meaningful exit codes | CLI | done (T10): reports, exit codes 0/1/2/3, `--quiet`, `--output`, `--var`, summary line |
| F11 | Desktop: remember last file, environment, window, recent files | desktop | done (T14): last file, environment, window, recent files |
| F12 | Desktop: several files at once, folder view | desktop | done (T15): tabs, restored at start, folder view |
| F13 | Desktop: history browser and response comparison | desktop | done |
| F14 | Editor intelligence: variable completion, undefined-variable and bad-reference diagnostics, find | desktop, plugin | partial: desktop done (T16: diagnostics, completion, find/replace); the plugin (T22) open |
| F15 | Desktop response extras: search in body, save to file, image/HTML preview | desktop | done (T17): search, save and image preview; HTML and SVG previews are not planned (no web view in Compose Desktop) |
| F17 | Edit environments in the UI | desktop | done (T20): editor panel in the desktop app |
| F18 | Plugin: completion, navigation, inspections | plugin | open |
| F19 | Plugin: richer response panel, remember environment, run all | plugin | partial: shared view-model, run guard, reset chain done; remember environment across restarts and run all done in code (T23, unverified by hand); the response panel is still plain text |
| F20 | Verification and release: plugin tried in a real IDE, CI on macOS/Windows/Linux, installers | all | partial: tests on all three OS, plugin build and best-effort installer packaging (T24) in CI; plugin checked by hand on Ultimate 2026.3 (T21); Community install, 2026.1 baseline and macOS/Linux installer QA open |
| F21 | User guide on GitHub Pages: step-by-step pages per interface (written, published by `pages.yml`), generated screenshots, links from the interfaces | docs, all UIs | partial: text guides done; screenshots, build check and links open ([user-guide-plan.md](user-guide-plan.md)) |
| F22 | Agent-friendly: an offline `httpc --check`, a skill that teaches coding agents the dialect and the CLI, an MCP server over the session | cli, new module, docs | partial: `--check` (T32) and the skill (T33) done; MCP server (T34) open |
| F23 | Bruno collections: run and convert `.bru` files and Bruno environments | adapters, cli | open (T35) |
| F24 | VS Code: diagnostics, completion and go to definition through a language server, requests run through `httpc` | new modules | open (T36, T37) |

**Later / out of scope for now:** GraphQL, WebSocket, gRPC, full OAuth2 flows, Postman/curl import (Bruno is F23), team sync.

## 2. Tasks

Done tasks are listed in the CHANGELOG, not here: T1 (file variables, settings), T2 (executor options), T3 (cookie jar),
T4 (`ResponseExtractor`), T5 (assertions), T6 (dynamic variables v2), T7 (Basic auth shortcut), T9 (`RunReport`, JSON and JUnit export),
T8 (multipart bodies), T12 (`view-model`), T15 (desktop tabs, restored tabs and folder view), T17 (desktop search, save and image preview), T18 (desktop history and compare), T20 (environment editor: file store, editor model and desktop panel), T16 (desktop editor intelligence: diagnostics, completion, find/replace), T32 (`httpc --check`), T33 (agent skill). Done as well: the review fixes R1-R13 (see [known-limitations.md](known-limitations.md) for what was left), including the
composition root, environment resolution, file confinement, the CI matrix part of T24 and build quality reports.

Each remaining task is listed once even when it serves several features. Size: S ≈ 1 day, M ≈ 2 days, L ≈ 3 days
(one developer working with Claude). "Needs" lists tasks that must be finished first.

| ID | Task | Serves | Size | Needs |
|---|---|---|---|---|
| T21 | **Plugin GUI verification:** checked by hand on Ultimate 2026.3 (IU-263.7307.200) with the 0.5.0-rc1 zip, see `intellij-plugin/README.md`; still open: a Community install and the 2026.1 baseline (the `Ctrl+Alt+R` run shortcut is still to be tried in an IDE) | F20, F19, F18 | S | – |
| T22 | **Plugin intelligence:** completion, go-to-request, inspections from T13 | F18, F14 | L | T21 (T13 done: `VariableAnalysis`) |
| T23 | **Plugin response panel:** remember the environment across restarts, run all: done in code, to be tried in an IDE (with T21's open checks) | F19 | M | T21 |
| T24 | **Packaging in CI** (rest of F20): done in `.github/workflows/release.yml` on tags `v*` (DMG/MSI/DEB best effort, plugin zip, CLI zip/tar); first real run on tag v0.5.0-rc1: all jobs passed | F20 | S | – |
| T27 | **Open the linked response** of a request (`<>` link, `ResponseLinks.latest`) in the plugin (the desktop part is done: **Linked response** button) | F9 | S | T21 |
| T25 | **Final cross-platform QA and release notes:** Windows MSI and plugin checked by hand on 0.5.0-rc1; CLI zip checked on 0.5.0-rc1 (`--list`, smoke test with a JUnit report); still open: macOS DMG, Linux DEB and the native file dialogs; desktop UI polish (button text, dropdown position, request list highlighting, env file editing) planned after 0.5.0 | F20 | M | all |
| T28 | **Guide build check:** build the Jekyll site on pull requests that change `docs/guide/**` and check links | F21 | S | – |
| T29 | **Guide screenshots:** desktop images generated headless from the real composables, CLI transcripts from real runs, plugin images by checklist | F21 | M | – |
| T30 | **`user-guide` build module:** Gradle tasks that generate the assets and assemble the site for the PR check and the deploy | F21 | M | T29 |
| T31 | **Links to the guide from the interfaces:** desktop Help, plugin Help link, one line in `httpc --help` | F21 | S | T29 (guide stable) |
| T34 | **MCP server** (`mcp-server` module over `SessionFactory`/`RunController`): `list_requests`, `run`, `run_all`, `list_environments`, `analyze`. Needs its safety model first: file roots and host allow-list, private environment values never returned, a way to confirm requests that change data. Plan the model before coding | F22 | M | T32, T33 (done) |
| T35 | **Bruno import:** a reader port in `core` with an adapter that turns `.bru` files and `environments/*.bru` into `RequestTemplate`s and `Environment`s (`httpc run collection/`, convert to `.http`); unsupported parts (JavaScript scripts, tests in Bruno syntax) are reported, never skipped silently; no write-back | F23 | M | – |
| T36 | **Language server:** `.http` diagnostics, completion and go to declaration over LSP in a new module, reusing `VariableAnalysis`, `HttpLineLexer` and the `view-model` helpers of T22 | F24 | L | T22 |
| T37 | **VS Code extension:** a thin client of T36 (TextMate grammar or LSP semantic tokens, run through `httpc`, environment picker); needs a Java runtime or a bundled one | F24 | L | T36 |

### Feature to task map

| Feature | Open tasks |
|---|---|
| F9 | T27 |
| F11 | T14 |
| F14 | T22 |
| F18 | T13, T21, T22 |
| F19 | T21, T23 |
| F20 | T25 (T21, T24 done) |
| F21 | T28, T29, T30, T31 |
| F22 | T34 (T32, T33 done) |
| F23 | T35 |
| F24 | T36, T37 |

Shared work, written once: T13 (F14, F18), T14 (F11, F12), T21 (F18, F19, F20).

## 3. Order

At most **3 tasks run at the same time**, each in its own worktree with a PR and a review. A wave starts when
the previous one is merged. Dependencies not listed in a wave are already satisfied.

| Wave | Tasks                                                                                                                      | Why together                                                 |
|------|----------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| 1    | T21 (plugin GUI check)                                                                                                     | Done on Ultimate 2026.3 (Community and 2026.1 baseline still open); its findings may reshape T22/T23 |
| 2    | T13 (variable analysis), T14 (desktop persistence)                                                                         | disjoint areas                                               |
| 3    | T23 (plugin panel)                                                                                  | (T8 done)                                                    |
| 4    | T22 (plugin intelligence)                                                                                                | needs T13 and T21 (T16, the desktop part, is done)           |
| 5    | T27 (plugin part: open linked response)                           | T20 is done                                                  |
| 6    | T25 (QA and release notes)                                                                                                 | needs everything                                             |
| 7    | T28 (guide check), T29 (screenshots), T30 (guide module), T31 (links) | any time after wave 5 for T29-T31; T28 can go first       |
| 8    | T34 (MCP server, after its safety model is written down); T35 (Bruno) | independent of the waves above; T32 (`--check`) and T33 (skill) are done |
| 9    | T36 (language server), then T37 (VS Code extension) | after T22 and the QA of wave 6 |

### Rules for every task

- Tests with the code; `./gradlew test` must stay green, and `./gradlew :quality-reports:qualityReports` must not lower the coverage floor.
- Update `docs/features.md`, `CHANGELOG.md` and the README of the module touched.
- Keep `core` free of third-party dependencies: new I/O goes behind a port in `core/port/out` with an adapter in `adapters`; front ends get their wiring from `SessionFactory` and `RunController`, not by hand.
- Commit per task (or per logical group), referencing the task ID.
- Tasks in the same wave avoid editing the same files; where they must (docs, changelog), keep edits to separate sections.

### Risks

- **T21 first on purpose:** the plugin was first used in a real IDE (Ultimate 2026.3) for 0.5.0-rc1; a Community install and the 2026.1 baseline are still unchecked. The findings may reshape T22/T23.
- **Windows packaging:** the MSI is built by the release workflow (windows-2022, WiX 3) and was installed and run by hand on 0.5.0-rc1; macOS and Linux installers are untested.
- **T26 writes files:** `>>` is the first feature that writes next to user data, so T26 must confine paths like reads do and never overwrite without `>>!`.
