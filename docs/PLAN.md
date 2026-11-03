# Plan

What is still missing, the tasks that deliver it, and the order to do them in.
Current state: see [features.md](features.md). History: see [../CHANGELOG.md](../CHANGELOG.md).

## 1. Features

| ID | Feature | Area |
|---|---|---|
| F1 | Response assertions (`# @assert ...`) and a test-runner mode with pass/fail results | core, CLI, UIs |
| F2 | File variables (`@host = localhost`) and request settings (`# @timeout`, `# @no-redirect`, `# @no-cookie-jar`) | core |
| F3 | Auth helpers (Basic encoding, Bearer shorthand) | core |
| F4 | Multipart and form bodies, file uploads | core |
| F5 | Cookie jar between requests | core |
| F6 | More dynamic variables (`$random.*`, `$env.X`, timestamp offsets, `$randomInt` range) | core |
| F7 | More extraction: XPath, regex, headers (for chaining and assertions) | core |
| F8 | Executor settings: redirects, timeouts, proxy | core |
| F9 | Response history and saved responses | core, UIs |
| F10 | CLI: `--json`, `--junit`, `--quiet`, `--output`, `--var`, meaningful exit codes | CLI |
| F11 | Desktop: remember last file, environment, window, recent files | desktop |
| F12 | Desktop: several files at once, folder view | desktop |
| F13 | Desktop: history browser and response comparison | desktop |
| F14 | Editor intelligence: variable completion, undefined-variable and bad-reference diagnostics, find | desktop, plugin |
| F15 | Desktop response extras: search in body, save to file, image/HTML preview | desktop |
| F16 | Cancel a running request | core, desktop |
| F17 | Edit environments in the UI | desktop |
| F18 | Plugin: completion, navigation, inspections | plugin |
| F19 | Plugin: richer response panel, remember environment, run all | plugin |
| F20 | Verification and release: plugin tried in a real IDE, CI on macOS/Windows/Linux, installers | all |

**Later / out of scope for now:** GraphQL, WebSocket, gRPC, full OAuth2 flows, Postman/curl import, team sync.

## 2. Tasks

Each task is listed once even when it serves several features. Size: S ≈ 1 day, M ≈ 2 days, L ≈ 3 days
(one developer working with Claude). "Needs" lists tasks that must be finished first.

| ID | Task | Serves | Size | Needs |
|---|---|---|---|---|
| T1 | **Request metadata in the parser:** `@var = value` lines, `# @setting` directives, a file-variable layer in `VariableResolver` | F2 | M | – |
| T2 | **Per-request executor options:** carry timeout/redirect/proxy settings from the template through `HttpRequest` to `JdkHttpExecutor` | F2, F8 | S | T1 |
| T3 | **Cookie jar** shared across a session, honoring `@no-cookie-jar` | F5 | S | T2 |
| T4 | **`ResponseExtractor` abstraction:** generalize `JsonExtractor` into JSONPath + XPath + regex + header lookups, used by chaining and assertions | F7, F1 | M | – |
| T5 | **Assertions engine:** parse `# @assert` directives, evaluate them against a response, report results | F1 | L | T1, T4 |
| T6 | **Dynamic variables v2** (`$random.*`, `$env.X`, offsets, `$randomInt` range) | F6 | S | – |
| T7 | **Auth shortcuts:** Basic user/password encoding, `Bearer` directive | F3 | S | T1 |
| T8 | **Multipart and form bodies** in parser and executor, with file parts | F4 | M | – |
| T9 | **`RunReport` model** (outcomes + assertion results + timings) with JSON and JUnit XML export | F1, F10, F9 | M | T5 |
| T10 | **CLI upgrade:** `--json`, `--junit`, `--quiet`, `--output`, `--var`, failure exit codes, summary line | F10, F1 | M | T9 |
| T11 | **History store:** outbound port + file adapter that saves reports and responses | F9, F13, F15 | M | T9 |
| T12 | **Shared view-model module:** move the pure JSON-tree flattening and status/size helpers out of `desktop` so the plugin can use them | F15, F19 | S | – |
| T13 | **Variable analysis in `core`:** list known variables (env, file, names) and find undefined variables and bad `{{name.response...}}` references | F14, F18 | M | T1 |
| T14 | **Desktop persistence:** settings port + JSON adapter in the user config dir; last file, environment, window, recents | F11 | M | – |
| T15 | **Desktop workspace:** several open files (tabs) and a folder view | F12 | L | T14 |
| T16 | **Desktop editor intelligence:** variable completion, diagnostics from T13, find/replace | F14 | L | T13 |
| T17 | **Desktop response extras:** body search, save to file, image/HTML preview | F15 | M | T12 |
| T18 | **Desktop history and compare:** browse stored responses, diff two | F13 | M | T11, T12 |
| T19 | **Cancelable requests:** async execution in the executor, cancel button | F16 | M | T2 |
| T20 | **Environment editor:** write support on the environment port and a UI to edit variables | F17 | M | T14 |
| T21 | **Plugin sandbox verification:** run `runIde` with a Community-like setup, fix highlighting, gutter icons, tool window, serialization conflicts | F20, F19, F18 | M | – |
| T22 | **Plugin intelligence:** completion, go-to-request, inspections from T13 | F18, F14 | L | T13, T21 |
| T23 | **Plugin response panel:** reuse T12, persistent environment, run all | F19 | M | T12, T21 |
| T24 | **CI and packaging matrix:** GitHub Actions on macOS/Windows/Linux running tests and building DMG/MSI/DEB and the plugin zip | F20 | S | – |
| T25 | **Final cross-platform QA and release notes:** manual pass of CLI, desktop (dialogs, MSI) and plugin; fix findings | F20 | M | all |

### Feature to task map

| Feature | Tasks |
|---|---|
| F1 | T4, T5, T9, T10 |
| F2 | T1, T2 |
| F3 | T7 |
| F4 | T8 |
| F5 | T3 |
| F6 | T6 |
| F7 | T4 |
| F8 | T2 |
| F9 | T9, T11 |
| F10 | T9, T10 |
| F11 | T14 |
| F12 | T15 |
| F13 | T11, T18 |
| F14 | T13, T16, T22 |
| F15 | T11, T12, T17 |
| F16 | T19 |
| F17 | T20 |
| F18 | T13, T21, T22 |
| F19 | T12, T21, T23 |
| F20 | T21, T24, T25 |

Shared work, written once: T1 (F2, F3, F14), T2 (F2, F8, F16), T4 (F1, F7), T9 (F1, F9, F10), T11 (F9, F13, F15),
T12 (F15, F19), T13 (F14, F18), T14 (F11, F12, F17), T21 (F18, F19, F20).

## 3. Timeline

At most **3 tasks run at the same time** (lanes A, B, C). Days are working days from the start; every dependency
above is finished before its dependent starts. Total: **18 working days** (about 3.5 weeks).

| Lane | Focus | Tasks in order (working days) |
|---|---|---|
| A | core language, then intelligence | T1 (1–2) → T2 (3) → T3 (4) → T7 (5) → T13 (6–7) → T22 (8–10) → T19 (11–12) → T16 (13–15) → T25 (17–18) |
| B | assertions, reports, CLI, history | T4 (1–2) → T5 (3–5) → T9 (6–7) → T10 (8–9) → T11 (10–11) → T18 (12–13) → T8 (14–15) |
| C | front ends and tooling | T21 (1–2) → T12 (3) → T6 (4) → T24 (5) → T14 (6–7) → T23 (8–9) → T15 (10–12) → T17 (13–14) → T20 (15–16) |

Day 16 only lane C is busy, and days 17–18 only T25 runs, so the maximum of 3 parallel tasks is never exceeded.

Dependency check for the tightest links: T5 starts day 3 after T1 and T4 end on day 2; T22 starts day 8 after T13 (day 7)
and T21 (day 2); T18 starts day 12 after T11 (day 11) and T12 (day 3); T25 starts day 17 after the last task (T20, day 16).

### Milestones

| After day | Milestone | Features done |
|---|---|---|
| 5 | **Language core:** file variables, settings, cookies, auth, assertions, extraction, dynamic variables v2; plugin verified in an IDE; CI green | F1 (engine), F2, F3, F5, F6, F7, F8 |
| 9 | **Testable from the CLI:** reports, JUnit/JSON output, shared view-model, desktop remembers its state, plugin panel | F1, F10, F11, F19 |
| 13 | **Power-user tooling:** history, cancel, workspace, editor intelligence in desktop and plugin | F9, F12, F13, F14, F16, F18 |
| 18 | **Release candidate:** multipart, environment editor, response extras, full cross-platform QA | F4, F15, F17, F20 |

### Rules for every task

- Tests with the code; `./gradlew test` must stay green.
- Update `docs/features.md`, `CHANGELOG.md` and the README of the module touched.
- Keep `core` free of third-party dependencies: new I/O goes behind a port in `core/port/out` with an adapter.
- Commit per task (or per logical group), referencing the task ID.
- Tasks on different lanes avoid editing the same files; where they must (docs, changelog), keep edits to separate sections.

### Risks

- **T21 first on purpose:** the plugin has never run in an IDE. Its findings may reshape T22/T23.
- **T5 is the largest single task** and gates T9–T11; if it slips, T13/T14-based work can move up to keep lanes busy.
- **Windows is unverified** until T24/T25: the MSI needs WiX 3.x on a Windows machine.
