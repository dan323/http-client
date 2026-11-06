# Plan

What is still missing, the tasks that deliver it, and the order to do them in.
Current state: see [features.md](features.md). History: see [../CHANGELOG.md](../CHANGELOG.md).
Review fixes that preceded this plan: [REMEDIATION.md](REMEDIATION.md) (all done).

## 1. Features

Status: **done**, **partial** (what is missing is in the task table) or **open**.

| ID | Feature | Area | Status |
|---|---|---|---|
| F1 | Response assertions (`# @assert ...`) and a test-runner mode with pass/fail results | core, CLI, UIs | partial: engine, CLI and reports done; desktop and plugin do not show assertion results |
| F2 | File variables (`@host = localhost`) and request settings (`# @timeout`, `# @no-redirect`, `# @no-cookie-jar`, `# @proxy`) | core | done |
| F3 | Auth helpers (Basic encoding; Bearer needs none) | core | done |
| F4 | Multipart and form bodies, file uploads | core | open |
| F5 | Cookie jar between requests | core | done |
| F6 | More dynamic variables (`$random.*`, `$env.X`, timestamp offsets, `$randomInt` range) | core | done |
| F7 | More extraction: XPath, regex, headers (for chaining and assertions) | core | done |
| F8 | Executor settings: redirects, timeouts, proxy | core | done |
| F9 | Response history and saved responses | core, UIs | open |
| F10 | CLI: `--json`, `--junit`, `--quiet`, `--output`, `--var`, meaningful exit codes | CLI | partial: `--report-json`, `--report-junit` and exit codes 0/1/2/3 done; `--quiet`, `--output`, `--var` open |
| F11 | Desktop: remember last file, environment, window, recent files | desktop | open |
| F12 | Desktop: several files at once, folder view | desktop | open |
| F13 | Desktop: history browser and response comparison | desktop | open |
| F14 | Editor intelligence: variable completion, undefined-variable and bad-reference diagnostics, find | desktop, plugin | open |
| F15 | Desktop response extras: search in body, save to file, image/HTML preview | desktop | open |
| F16 | Cancel a running request | core, desktop | partial: interrupting the calling thread cancels the request (core, adapters); no cancel button yet |
| F17 | Edit environments in the UI | desktop | open |
| F18 | Plugin: completion, navigation, inspections | plugin | open |
| F19 | Plugin: richer response panel, remember environment, run all | plugin | partial: shared view-model, run guard, reset chain done; remember environment across restarts and run all open |
| F20 | Verification and release: plugin tried in a real IDE, CI on macOS/Windows/Linux, installers | all | partial: tests on all three OS and plugin build in CI; GUI of the plugin, installers in CI and cross-platform QA open |
| F21 | Saved responses linked from the `.http` file, as in the JetBrains plugin: `>> file` / `>>! file` save the response body, `<> file` links a request to its latest stored response | core, UIs | open |

**Later / out of scope for now:** GraphQL, WebSocket, gRPC, full OAuth2 flows, Postman/curl import, team sync.

## 2. Tasks

Done tasks are listed in the CHANGELOG, not here: T1 (file variables, settings), T2 (executor options), T3 (cookie jar),
T4 (`ResponseExtractor`), T5 (assertions), T6 (dynamic variables v2), T7 (Basic auth shortcut), T9 (`RunReport`, JSON and JUnit export),
T12 (`view-model`). Done as well: the review fixes R1-R13 ([REMEDIATION.md](REMEDIATION.md)), including the
composition root, environment resolution, file confinement, the CI matrix part of T24 and build quality reports.

Each remaining task is listed once even when it serves several features. Size: S ≈ 1 day, M ≈ 2 days, L ≈ 3 days
(one developer working with Claude). "Needs" lists tasks that must be finished first.

| ID | Task | Serves | Size | Needs |
|---|---|---|---|---|
| T8 | **Multipart and form bodies** in parser and executor, with file parts (file reads go through the confined `FileReader`) | F4 | M | – |
| T10 | **CLI rest:** `--quiet`, `--output`, `--var`, summary line (reports and exit codes are done) | F10 | S | – |
| T13 | **Variable analysis in `core`:** list known variables (env, file, names) and find undefined variables and bad `{{name.response...}}` references | F14, F18 | M | – |
| T14 | **Desktop persistence:** settings port + JSON adapter in the user config dir; last file, environment, window, recents | F11 | M | – |
| T15 | **Desktop workspace:** several open files (tabs) and a folder view | F12 | L | T14 |
| T16 | **Desktop editor intelligence:** variable completion, diagnostics from T13, find/replace | F14 | L | T13 |
| T17 | **Desktop response extras:** body search, save to file, image/HTML preview; also show assertion results (F1) | F15, F1 | M | – |
| T18 | **Desktop history and compare:** browse stored responses, diff two | F13 | M | – (T11 done: `HistoryStore` / `FileHistoryStore`) |
| T19 | **Cancel button:** desktop (and plugin) cancel of the running request; core and the executor already cancel on interrupt | F16 | S | – |
| T20 | **Environment editor:** write support on the environment port and a UI to edit variables | F17 | M | T14 |
| T21 | **Plugin GUI verification:** the sandbox IDE loads without errors; still to check by hand: highlighting, gutter icons, tool window, actions, in a Community IDE | F20, F19, F18 | S | – |
| T22 | **Plugin intelligence:** completion, go-to-request, inspections from T13; show assertion results | F18, F14, F1 | L | T13, T21 |
| T23 | **Plugin response panel:** remember the environment across restarts, run all | F19 | M | T21 |
| T24 | **Packaging in CI** (rest of F20): build DMG/MSI/DEB and the plugin zip on the matching OS (tests on three OS already run) | F20 | S | – |
| T26 | **Saved responses and links:** parse `>> file` / `>>! file` (today they are skipped with a parser warning, like other handlers) and `<> file`; write the response body to the named file (`>>` never overwrites, `>>!` does) through a port, with paths confined like `< path` (`FileAccessPolicy`); keep a `<> file` link to the latest stored response up to date, with the response files kept in a history folder next to the `.http` file (`.http-history/` via the T11 `HistoryStore`); open the linked response from desktop and plugin | F21, F9 | M | – (T11 done: `HistoryStore` / `FileHistoryStore`) |
| T25 | **Final cross-platform QA and release notes:** manual pass of CLI, desktop (dialogs, MSI) and plugin; fix findings | F20 | M | all |

### Feature to task map

| Feature | Open tasks |
|---|---|
| F1 | T17, T22 |
| F4 | T8 |
| F9 | T26 |
| F10 | T10 |
| F11 | T14 |
| F12 | T15 |
| F13 | T18 |
| F14 | T13, T16, T22 |
| F15 | T17 |
| F16 | T19 |
| F17 | T20 |
| F18 | T13, T21, T22 |
| F19 | T21, T23 |
| F20 | T21, T24, T25 |
| F21 | T26 |

Shared work, written once: T13 (F14, F18), T14 (F11, F12, F17), T21 (F18, F19, F20).

## 3. Order

At most **3 tasks run at the same time**, each in its own worktree with a PR and a review. A wave starts when
the previous one is merged. Dependencies not listed in a wave are already satisfied.

| Wave | Tasks | Why together |
|---|---|---|
| 1 | T21 (plugin GUI check) | Done by hand in a real IDE; its findings may reshape T22/T23 |
| 2 | T13 (variable analysis), T14 (desktop persistence), T26 (saved responses, needs T11) | disjoint areas |
| 3 | T8 (multipart), T19 (cancel button), T23 (plugin panel) | disjoint areas |
| 4 | T22 (plugin intelligence), T16 (desktop editor), T15 (workspace) | need T13 / T14 |
| 5 | T17 (desktop extras), T18 (history browser), T20 (environment editor), T10 (CLI rest), T24 (packaging) | up to three at a time |
| 6 | T25 (QA and release notes) | needs everything |

### Rules for every task

- Tests with the code; `./gradlew test` must stay green, and `./gradlew :quality-reports:qualityReports` must not lower the coverage floor.
- Update `docs/features.md`, `CHANGELOG.md` and the README of the module touched.
- Keep `core` free of third-party dependencies: new I/O goes behind a port in `core/port/out` with an adapter in `adapters`; front ends get their wiring from `SessionFactory` and `RunController`, not by hand.
- Commit per task (or per logical group), referencing the task ID.
- Tasks in the same wave avoid editing the same files; where they must (docs, changelog), keep edits to separate sections.

### Risks

- **T21 first on purpose:** the plugin has never been used in a real IDE window. Its findings may reshape T22/T23.
- **Windows packaging is unverified** until T24/T25: the MSI needs WiX 3.x on a Windows machine.
- **T26 writes files:** `>>` is the first feature that writes next to user data, so T26 must confine paths like reads do and never overwrite without `>>!`.
