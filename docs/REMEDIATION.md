# Remediation pre-plan

**Status: all tasks R1–R13 are done and merged (PRs #10–#24).** Source: three context-free read-only reviews of `master` after PRs #1–#9 (core + docs; adapters + cli + mock-server + build/CI; desktop + view-model + intellij-plugin). Each task started by reproducing its findings with a failing test. What was fixed is in [../CHANGELOG.md](../CHANGELOG.md); what was deliberately left is under Leftovers below.

Verdict on the architecture: the hexagon holds (`core` has no third-party dependencies, no outward imports, all I/O through ports). The problems are at the seams between features and in the three front ends, which each wire the core their own way.

Workflow: max 3 tasks in parallel, one agent and worktree per task, PR per task, review by a fresh agent, fixes by a fresh agent. Task IDs `R1`–`R13` avoid clashing with `T1`–`T26` in [PLAN.md](PLAN.md).

## Decisions (all taken as recommended)

| # | Question | Affects | Decision |
|---|----------|---------|----------|
| D1 | Confine `< path` file bodies to the `.http` file's folder? | R5 | Yes by default, with an allowlist like `$env` (`--allow-file-root`, `HTTPC_ALLOW_FILE_ROOTS`); JetBrains does not confine, so this is a deliberate deviation |
| D2 | Should `# @timeout` and a default timeout bound the whole exchange (headers and body)? | R4 | Yes; default connect timeout 30 s, no default total timeout |
| D3 | Where does JUnit XML rendering live? | R8 | Moved to `adapters` next to `JsonRunReport`; `RunReport` stays in `core` |
| D4 | Unknown/typo'd method (`GTE http://x`): error or keep treating as URL? | R2 | Error with line number (plus a "did you mean" hint for one-letter typos) |
| D5 | `> {% ... %}` response handlers and `<> file`: ignore silently, warn, or fail? | R2 | Warn (`HttpFile.warnings`, printed by the CLI) and do not send them as body |
| D6 | "(none)" environment in the plugin: really no environment, or a named option? | R6, R9 | Really none (variables from the file only), modelled as `EnvironmentChoice.None` / `Named`; never fall back silently |

## Tasks

| Task | What | PR |
|------|------|----|
| R1 | Dependency scan through file variables, assertions and settings; dotted request names; variable cycles detected, expansion bounded; substituted values not re-expanded | #12 |
| R2 | Lexer and parser share `HttpLineSyntax`; unknown method and header without `:` are errors with line numbers; `> {% %}` / `> file` / `<> file` skipped with warnings | #11 |
| R3 | `runAll` keeps the interrupt flag, `RequestSession` thread-safe, assertion reasons kept, `RedirectFollower` and `RequestOptionsParser` extracted | #13 |
| R4 | Timeout bounds the whole exchange, 30 s connect timeout, interrupt cancels; credential headers checked on cross-host redirects (no change needed) | #10 |
| R5 | `< path` confined to the `.http` folder with a validated allowlist (`FileAccessPolicy`) | #15 |
| R6 | `EnvironmentLocator` port + `UpwardEnvironmentLocator`, explicit `EnvironmentChoice`, one `$env` allowlist for all front ends, JSON `null` and malformed-file handling | #14 |
| R7 | `SessionFactory` composition root, `RunController` and `OutcomeDisplay` in `view-model` | #17 |
| R8 | `ReportWriter` port + `AtomicReportWriter`, JUnit XML moved to `adapters`, report path checks, exit code precedence, `RunReport.passed` | #24 |
| R9 | Plugin: run guard, bounded and evicted sessions (`ClientState`), listener disposal, gutter saves first, O(1) gutter line lookup, headless tests | #22 |
| R10 | Desktop: atomic run guard, no file switching or editing while running, env/selection/save-error handling, off-thread cached response models | #23 |
| R11 | Failure messages scrubbed of substituted values; hardened and bounded XPath; charset and repeated-header extraction | #18 |
| R12a | Version catalog, CI on Ubuntu/Windows/macOS on the JDK 21 toolchain, plugin build job | #19 |
| R12b | Docs refresh (this change) | – |
| R13 | `quality-reports` project: aggregated tests, merged JaCoCo with floor, PIT on `core`, CI job | #20 |

Also merged: #16 (build warnings: v2 `runComposeUiTest`, shared `Json` in the mock server) and #21 (symlink-boundary and broken env-link tests).

## Leftovers

Known and accepted; none blocks a release.

- XPath: a timed-out evaluation cannot be interrupted, so its daemon thread keeps running until it finishes (the caller gets "not found" after 2 s).
- Plugin: the VFS listener (forget on delete/move), the gutter icon and the tool window are not tested without an IDE; the sandbox IDE only loads without errors (T21 in `PLAN.md`).
- CLI `samePath` compares file names case-insensitively within one folder, so two reports differing only by case are rejected even on case-sensitive file systems (conservative).
- File confinement: someone with write access inside an allowed folder can still race the check by swapping a parent directory for a symlink.
- With `@no-cookie-jar` the JDK client follows redirects itself and its handling of explicit credential headers depends on the JDK version.
- A user-set `Host`, `Content-Length`, `Connection`, `Expect` or `Upgrade` header is dropped silently by the JDK client (documented, not warned about).
- Mock server (test fixture, R12 low priority, not done): it binds in the constructor instead of `start()` and a non-numeric port argument silently falls back to 8080; the other R12 cleanups (bad percent-escape handling, default single-threaded executor and unbounded body read, repeated query keys as lists) were not looked at again.
- No UI for the `$env` and file-root allowlists in desktop and plugin (system property / environment variable only).

## Not planned

- New features were not part of this plan; they resume with `PLAN.md`.
