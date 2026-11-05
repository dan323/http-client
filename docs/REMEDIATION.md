# Remediation pre-plan

Source: three context-free read-only reviews of `master` after PRs #1–#9 (core + docs; adapters + cli + mock-server + build/CI; desktop + view-model + intellij-plugin). Findings come from reading the code; **none were reproduced yet**. The first step of every task is to reproduce the finding with a failing test, and to drop it (with a note in the PR) if it turns out to be wrong.

Verdict on the architecture: the hexagon holds (`core` has no third-party dependencies, no outward imports, all I/O through ports). The problems are at the seams between features and in the three front ends, which each wire the core their own way.

Rules for this plan: same workflow as `PLAN.md` (max 3 tasks in parallel, one agent and worktree per task, PR per task, review by a fresh agent, fixes by a fresh agent). Task IDs are `R1`–`R12` to avoid clashing with `T1`–`T25`.

## Decisions needed before starting

| # | Question | Affects | Recommendation |
|---|----------|---------|----------------|
| D1 | Confine `< path` file bodies to the `.http` file's folder? | R5 | Yes by default, with an opt-out/allowlist like `$env` (JetBrains does not confine, so this is a deliberate deviation) |
| D2 | Should `# @timeout` and a default timeout bound the whole exchange (headers and body)? | R4 | Yes; default connect timeout 30 s, no default total timeout |
| D3 | Where does JUnit XML rendering live? | R8 | Move to `adapters` next to `JsonRunReport` (all report formats in one place); `RunReport` stays in `core` |
| D4 | Unknown/typo'd method (`GTE http://x`): error or keep treating as URL? | R2 | Error with line number |
| D5 | `> {% ... %}` response handlers and `<> file`: ignore silently, warn, or fail? | R2 | Warn (parser warning list) and do not send them as body |
| D6 | "(none)" environment in the plugin: really no environment, or a named option? | R6, R9 | Really none (variables from the file only); never fall back silently |

## Findings and tasks

Severity: **H** high, **M** medium, **L** low. "Src" is the review that found it (C core, A adapters/cli/mock, U ui modules).

### R1 — Dependency scan and variable resolution (core)
- M (C) `withDependencies` scans only URL, header values and body: misses references that arrive through file variables (`@token = {{login.response...}}`), `# @assert` expressions and settings. `--request` and the plugin's "run this request" then fail with `Undefined variable`.
- M (C) Request names with a dot (`# @name auth.login`) are missed by the dependency regex `[^{}\s.]+`.
- M (C) A self-referencing variable (`@a = {{a}}`) is sent as a literal `{{a}}` at depth 10; fan-out is exponential (k^10). Detect cycles and fail with a clear error; bound total expansions.
- L (C) Environment values containing literal `{{` are re-interpreted (a secret with braces throws `Undefined variable`). Expand only variable values written in the file, not substituted values.
- Tests: `DependenciesTest` (file variables, dotted names, assertions, cycles), a real cyclic test (the current one is vacuous).
- Files: `Dependencies.kt`, `VariableResolver.kt`, `RequestSession.kt` (small). Depends on: nothing.

### R2 — Lexer and parser agreement (core)
- M (C) `HttpLineLexer` treats `@var = value` lines as the URL, so the real `GET` line is lexed as a header and the gutter icon lands on the `@host` line. Make lexer and parser share one line classifier.
- L (C) Method set duplicated in `HttpFileParser` and `HttpLineLexer`; lexer modes are `Int` constants (use a sealed type or enum).
- L (C) Parser accepts malformed input: `GTE http://x` becomes a GET with a bad URL (D4); header lines without `:` are dropped; `> {% ... %}` and `<> file` lines are sent as body (D5); the lexer highlights any body line starting with `<` as a file reference.
- Tests: lexer cases for file variables, parser error cases with line numbers.
- Files: `HttpLineLexer.kt`, `HttpFileParser.kt`. Depends on: D4, D5.

### R3 — Session robustness and structure (core)
- L (C) `runAll` catches `Exception`, swallowing `InterruptedException`: the flag is lost and remaining requests are sent. Rethrow and restore the flag (prerequisite for the cancel feature, F16/T19).
- L (C) `RequestSession` is not thread-safe (`responses` is a plain map) while `CookieJar` is synchronized. Make it safe or document and enforce single-threaded use.
- L (C) The assertion scrub replaces the reason of genuinely invalid assertions (bad regex, non-numeric `<`) once a variable is used. Scrub only the resolved values, keep the reason.
- Smells (C): split the redirect state machine out of `RequestSession` (own class, own tests, with a test locking it to the executor's redirect behaviour); move option parsing out of `RequestRunner` into a `RequestOptionsParser`; merge the duplicated catch blocks in `evaluateAssertions`; `DynamicVariables.DEFAULT` shared static with a `SecureRandom`.
- Files: `RequestSession.kt`, `RequestRunner.kt`. Depends on: R1 (same files; do it after).

### R4 — Executor safety (adapters)
- M (A) The timeout covers only the response headers; `BodyHandlers.ofByteArray()` reads without limit. A server that sends headers and stalls hangs the CLI, even with `# @timeout`. No default `connectTimeout` either (D2).
- M (A) Check, with a test against a real two-host setup, whether `Authorization` and `Cookie` are re-sent on a cross-host redirect when the jar is off (`Redirect.NORMAL`). `JdkRedirectHeaderTest` may already cover part of this.
- L (A) `InterruptedException` is not handled (restore the flag; needed by cancel).
- L (A) A user-set `Host` header (and other restricted headers) is dropped silently; warn or document.
- L (A) `JdkHttpExecutor` never closed by front ends (solved structurally in R7).
- Files: `JdkHttpExecutor.kt`. Depends on: D2.

### R5 — File-read confinement (adapters)
- M (A, C) `LocalFileReader` resolves `< ../../.ssh/id_rsa` and absolute paths and uploads the bytes. Confine to the `.http` file's folder by default with an explicit opt-out/allowlist (D1); clear error naming the rejected path; document in `docs/features.md`.
- Files: `LocalFileReader.kt`, front-end wiring for the opt-out. Depends on: D1; wiring touches R7 files, so run after R6.

### R6 — Environment resolution in one place (core port + adapters)
- M (U) The plugin searches parent folders for the env file; desktop and the CLI look only next to the file, so the same project resolves different environments per front end. Add an `EnvironmentLocator` (port in `core/port/out`, adapter in `adapters`) and use it everywhere.
- M (U) Plugin "(none)" silently falls back to the first environment (could be prod), a stale name does the same, and setting the combo programmatically fires its listener and resets the choice (D6). Model the choice explicitly (`None` / `Named(x)` / `Default`).
- L (A) CLI ignores `HTTPC_ALLOW_ENV` / `-Dhttpclient.allowEnv` that desktop and plugin honour: one allowlist policy for all three.
- L (A) JSON `null` becomes the string `"null"`; malformed env JSON gives a generic error without the file name.
- Files: `EnvironmentRepository`/`JsonEnvironmentRepository`, `SystemEnvironmentVariables`, new locator. Depends on: D6.

### R7 — One composition root and shared run logic (adapters, view-model, core)
- M (U) CLI, desktop and plugin each build executor, runner, dynamic variables, file reader, session and extractor on their own (already drifted: desktop has no session cache, so the login request reruns on every Send).
  - Add a factory in `adapters` that assembles the session and owns/close the executor.
  - Move "which request is at the caret", "run selected request with dependencies" and session-per-(file, env) caching out of the UI modules (`core` use case or small class in `view-model`).
  - Move the `RunOutcome` → display rows mapping (status colour class, header text, pretty-or-raw) into `view-model`; UI modules keep only toolkit colours.
- Files: `cli/Main.kt`, `desktop/Workspace.kt`, `intellij-plugin/HttpClientService.kt`, `view-model`. Depends on: R6, R3 (session API settles first).

### R8 — CLI and reports (cli, adapters)
- M (A) Report files are created owner-only (0600) by `createTempFile` + move; set permissions deliberately.
- L (A) `--report-*` may point at the input `.http` file (overwrites it); `--list` with a report option silently writes nothing; failed run + unwritable report exits 3 instead of 1 (define precedence).
- Smells (A): CLI recomputes pass/fail instead of `RunReport.passed`; report writing is file I/O in `Main.kt` with no port (add `ReportWriter` port); JUnit XML location (D3); tests for `--report-*` parsing and the same-path check.
- Files: `cli/Main.kt`, `JUnitXmlReport.kt`, `JsonRunReport.kt`. Depends on: R7 (same `Main.kt`), D3.

### R9 — Intellij plugin fixes (intellij-plugin)
- M (U) No "already running" guard: two quick triggers race on a shared non-thread-safe session and overwrite each other's report.
- L (U) Gutter icon does not save documents first (unlike the action); `RunLineMarker` is O(n²) (use cached line starts / `document.getLineNumber`); `HttpClientService` annotated `@Service` and registered in `plugin.xml` (unconfirmed, keep one; drop deprecated `secondary="true"`); `onReport` listener never unregistered (take a `Disposable`); sessions never evicted (drop when the file changes or on project close); `getOrPut` builds a throwaway executor/runner on every call.
- Tests: the plugin only tests the lexer. Add tests for environment choice, session reuse, `resetChain`, and the run guard (headless).
- Depends on: R6, R7.

### R10 — Desktop fixes (desktop)
- M (U) `AppState.run` guard is not atomic; outcomes are appended with a read-modify-write; opening another file while running mixes outcomes into the new file. Make the guard atomic, tag outcomes with a run id, or disable file switching while running.
- L (U) Failed env refresh keeps the previous file's environments; a stale `selected` index runs nothing with no message; a save error is wiped when the run starts; `ResponsePane` caches parsed outcomes forever and parses JSON/decodes the body in composition on the UI thread; the request list re-parses the whole text on every recomposition.
- Tests: `AppState` concurrency and file-switch tests.
- Depends on: R7.

### R11 — Redaction and extraction hardening (core)
- L (C, A) Failure messages carry the raw exception text, and `URI.create` errors include the full resolved URL, so a token in a query string can reach JUnit/JSON reports. Scrub resolved values from `RunOutcome.Failure.message` the same way assertions are scrubbed.
- L (C) XPath has no time bound and `XPathFactory` is not hardened like the DOM factory; `ResponseExtractor` always decodes UTF-8 and returns only the first value of repeated headers (`Set-Cookie`).
- Depends on: R3.

### R12 — Build, CI, docs, mock server (build, docs)
- L (A) Version catalog (kotlinx-serialization repeated in four files, Kotlin in two); CI runs on Ubuntu only and never builds `:intellij-plugin`; add Windows/macOS jobs (the Windows `$env` path and atomic-move fallback are untested); `JdkHttpExecutor`-level redirect tests run on JDK 21.
- L (C) Docs drift: `docs/features.md` still says "Currently available through the httpc CLI", says assertions "will" use the expressions, and its CLI synopsis omits `--report-json`, `--report-junit`, `--allow-env`; `docs/PLAN.md` lists T1–T9 as open and has timelines for finished tasks. Update after the code tasks land.
- Mock server is a test fixture only (not packaged: the `dist*` tasks are disabled in `mock-server/build.gradle.kts`, uncommitted at the time of writing). Low-priority cleanups: bad percent-escape returns 400 instead of dropping the connection, multi-threaded executor and bounded body read, repeated query keys as lists, bind in `start()`, reject a non-numeric port argument.
- Depends on: everything for the docs part; the build/CI part has no dependency.

## Dependency graph

```
R1 ──► R3 ──► R11
R2
R4
D1 ──► R5 (after R6)
D6 ──► R6 ──► R7 ──► R8
                 ├─► R9
                 └─► R10
all code tasks ──► R12 (docs)
```

## Timeline (max 3 in parallel)

Each wave starts when the previous wave is merged (conflict areas are in brackets).

| Wave | Tasks | Why together | Needs |
|------|-------|--------------|-------|
| 1 | R1 (core resolver), R2 (core lexer/parser), R4 (adapters executor) | disjoint files | D2, D4, D5 |
| 2 | R3 (core session), R6 (env resolution), R5 (file confinement) | R3 needs R1; R6 and R5 touch different adapters | D1, D6 |
| 3 | R7 (composition root), R11 (redaction), R12a (build/CI) | R11 only touches core after R3; R12a touches build files only | wave 2 merged |
| 4 | R8 (cli/reports), R9 (plugin), R10 (desktop) | all depend on R7; disjoint modules | R7 merged, D3 |
| 5 | R12b (docs refresh), leftovers | docs must describe the final state | everything |

Estimated: 5 waves. Waves 1 and 4 are the largest.

## Not planned

- Reproducing every finding up front: each task starts with a failing test and may close its finding as "not a bug".
- New features: this plan only fixes issues; feature work (`PLAN.md` T7, T10, ...) resumes afterwards, except that R3 and R4 are prerequisites for the cancel feature.
