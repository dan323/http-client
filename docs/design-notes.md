# Design notes

Implementation and security detail behind [features.md](features.md), the user reference. The sections have the same names as there. Nothing here is needed to write a `.http` file; it explains how things work and where the limits come from. Class names are those of the `core` and `adapters` modules (see [AGENTS.md](../AGENTS.md)).

## Contents

- [`.http` files](#http-files)
- [Variables](#variables)
  - [Editor diagnostics (analysis)](#editor-diagnostics-analysis)
  - [Request settings](#request-settings)
  - [Cookie jar](#cookie-jar)
  - [Assertions](#assertions)
  - [Run reports](#run-reports)
  - [Saved responses](#saved-responses)
  - [Run history store](#run-history-store)
  - [Dynamic variables](#dynamic-variables)
- [Request chaining](#request-chaining)
- [Command line](#command-line)
- [Desktop app](#desktop-app)

Where a passage below is quoted from the old features.md, it is shown as a block quote; the rest is paraphrase that points at the code.

## `.http` files

Security of `< path` and `<@ path` reads (the user-facing rules are in [features.md](features.md#http-files)):

> Residual risk: the file is opened without following a final symlink and re-verified, but someone with write access inside an allowed folder could still race the check by swapping a parent directory for a symlink.

## Variables

### Editor diagnostics (analysis)

Original description of the API (the features page keeps the table of codes and the rules for what is scanned):

> `VariableAnalysis` (`core`, pure and IO-free) checks the `{{...}}` of a `.http` text without running anything, for the
> editors (desktop and plugin use it in later tasks). `VariableAnalysis.analyze(text, environmentNames)` takes the
> text and the *names* of the available environment variables (values never enter the analysis; pass the union of
> all environments for a lenient editor, nothing for "no environment") and offers:
>
> - `diagnostics`: `Diagnostic(code, severity, message, range)`; `range` is a `SourceRange` in UTF-16 chars (offset, length,
> 0-based line and column; tabs and CRLF count as in the text). Messages name variables and requests, never values.
> - `knownVariablesAt(offset)`: file variables defined above, environment variables, `name.response.` prefixes of the
> requests above, and the dynamic variable templates (`$uuid`, `$timestamp`, `$random.integer(from, to)`, `$env.NAME`, ...).
> - `findUsages(name)`, `findVariableUsages`, `findRequestUsages`, `occurrenceAt(offset)`: every use, `@x =` definition,
> `# @name` declaration and `{{name.response...}}` prefix, each range covering exactly the name (for rename).

- The run-time rules are implemented by `VariableResolver`; the analysis shares its reference pattern and lookup order. A consistency test runs `samples/` and compares with run time.
- Analysis time is linear in the text size.

### Request settings

Original wording of the introduction:

> The parser records them on the request
> (`RequestTemplate.settings`); the following are honoured per request by the executor (carried as `HttpRequest.options`)

Other details:

- Interrupting the calling thread cancels the in-flight request and fails with a `CancellationException` (the interrupt flag is kept).
- The redirect credential handling is the same on every JDK because, in the original words: the JDK client is never left to follow redirects, so the credential handling is the same on every JDK
- `JdkHttpExecutor` sends with `Redirect.NEVER` clients (redirects are followed by `RedirectFollower`), reusing one derived client per proxy when the injected client needs one or a request names an `@proxy`, and copies the injected client's connect timeout, SSL, authenticator, cookie handler, executor and HTTP version; request priority cannot be copied.

### Cookie jar

- The session that owns the jar is the `RequestSession`; the jar itself is `CookieJar` in `core`. In the desktop app and plugin the sessions are kept by the shared `RunController`. Original fragments: `Each `RequestSession` (one CLI run;`, `kept by the shared `RunController` so when`, `owns an in-memory `CookieJar` in `core``.
- Redirects:

> The JDK
> client is never asked to follow redirects (its forwarding of such headers varies by JDK version; `JdkHttpExecutor` itself follows
> through the same code), so this holds on every JDK.

- Public Suffix List (the user page only says the list is a snapshot):

> Public Suffix List: `core` stays free of third-party libraries, so the list is a bundled classpath resource,
> `core/src/main/resources/com/dan323/httpclient/core/public_suffix_list.txt` (ICANN and private sections, rules only; the first
> line records the list version and commit), read by `PublicSuffixList` (wildcard and exception rules supported; a name with no
> matching rule counts as a one-label suffix, as in the PSL algorithm). The list is a snapshot and goes stale as registries change;
> to update it, download `https://publicsuffix.org/list/public_suffix_list.dat`, drop comment lines (`//`), blank lines and
> anything after whitespace, put a header line with the new `VERSION` and `COMMIT` on top, replace the file and run `./gradlew :core:test`.

- Library limitation:

> `@no-cookie-jar` cannot disable a `CookieHandler` configured
> on an `HttpClient` injected into `JdkHttpExecutor` (that handler still sends and stores on its own).

### Assertions

Original bullet on sessions and outcomes (the features page keeps the behaviour in one bullet):

> - `RequestSession` is safe for concurrent use: runs are serialised (one request at a time per session) and
> interrupting the running thread stops `runAll` without sending the queued requests.
> - `RequestSession.runAll` evaluates them and attaches one `AssertionResult` (passed, expected, actual, message) each to
> `RunOutcome.Success.assertions`. A request that throws reports `RunOutcome.Failure.assertionsNotRun`.
> `runChained` (single-request runs) evaluates only the requested request; its prerequisites run for their responses
> and their assertions are neither evaluated nor reported. Reused dependencies are not re-asserted.

### Run reports

> `RunReport.from(outcomes)` (in `core`) builds a pure summary of a run: one entry per request (name, method, URL as
> written in the file, status, duration, assertion results, assertions not run, error, optional `httpError`) plus totals
> and an overall pass/fail. A request passes when it completed and all its assertions passed; when the run is built with
> `failOnHttpError` (CLI `--fail-on-http-error`) it also needs a status below 400, otherwise the entry carries
> `httpError` (`"HTTP 503"`). Exporters, both in `adapters`:
> `JUnitXmlReport` (plain string building with XML escaping; illegal XML characters are dropped) and `JsonRunReport`
> (kotlinx-serialization, also parses a report back). Files are written through the `ReportWriter` port (`core`),
> implemented by `AtomicReportWriter` (`adapters`).

Other fragments:

- The pass/fail decision is `RunReport.passed`.
- Original JSON wording: Top-level `version` (currently 1); `JsonRunReport.parse` rejects a newer version. `passed` and `totals` (and each
entry's `passed`) are derived: written for consumers, ignored and recomputed by `parse`.

Report building is `RunReport.from(outcomes)` in `core` (pure); exporters are `JUnitXmlReport` (plain string building with XML escaping) and `JsonRunReport` (kotlinx-serialization) in `adapters`, written through the `ReportWriter` port by `AtomicReportWriter`.

### Saved responses

- Confinement is `FileAccessPolicy` (the same policy as `< path` bodies: the `.http` file's folder plus the allowlist). Original fragment: ``FileAccessPolicy`:`
- Ports and adapters: The port is `ResponseSaver` (`core`), `LocalResponseSaver` (`adapters`). Results are carried by `RunOutcome.Success.saveError` and `savedTo`; original wording:

> `RunOutcome.Success.saveError` holds the
> message (names the path, hides substituted values, never contains the body) and `savedTo` the path written.

- The path shown after a save is `RunOutcome.Success.savedTo` (also the CLI line).
- Atomic publishing and the residual race, original bullet:

> - The temp file (random name, owner-only) is written in the final, already verified folder; `>>!` is one atomic rename
> inside that folder, and `>>` hard-links the finished temp file to the first free name (a link fails if the name exists,
> so nothing is clobbered and no empty or partial file is ever visible), then removes the temp name (without hard links a
> plain move without replace is used). Right before publishing, the folder's real path must still be the verified one
> and the temp file must be in it. If a folder was swapped for a symlink after validation, the source (named by its random
> temp name) does not exist at the new location, so the rename/link fails and nothing outside is touched, also for `>>!`.
> Residual race (same family as `< path` reads; the JDK has no openat): an attacker who can write inside the allowed
> folders, can observe the random temp name and swaps the folder in the microseconds between the last check and the
> syscall, while placing a file of that name at the new location, can still make one save land outside; that is detected
> afterwards, a `>>` file is removed, a `>>!` replacement cannot be restored and the error says so.

- Application point, original bullet:

> - Bare `RequestSession.run` does not save or link; `>>`/`>>!`/`<>` are applied by `runAll`/`runChained`, once per request.

- Desktop app history, original fragment: `; the desktop  app passes a `FileHistoryStore` like the CLI and the plugin, so `<>` links are recorded there too. ... recorded there too.` (it passes a `FileHistoryStore` like the CLI and the plugin).
- `<>` links, original paragraph:

> `<> path` links: JetBrains rewrites the `.http` file to add a `<>` line per stored response. This tool deliberately
> never edits your `.http` file. The `<> path` text is kept as written (`RequestTemplate.responseLinks`, the path itself
> is not used); instead, after a run of a request that has a `<>` line, its latest response is recorded in the history
> index (`HistoryStore.recordLinked`), and `ResponseLinks.latest(store, httpFile, request)` returns the latest stored
> response of that request (`HistoryStore.latestLinked`). A request is identified by its `# @name` (duplicate names
> share one link: the newest run wins) or, unnamed, by the line where it starts (so adding lines above it breaks the
> link). Recording is opt-in: `SessionFactory(history = FileHistoryStore())` (or any `HistoryStore`, with an injectable
> root); the CLI and the plugin pass it, so the response of a linked request lands in `.http-history/`. The history keeps
> the newest 50 runs per file, and additionally the newest record of every `<>` key even when it is older than that, so a
> link keeps resolving however often other requests run (at most one extra run folder per distinct linked request; an
> entry whose request lost its `<>` line or was removed stays until you delete it from `.http-history/`). Only a record whose body can still be read
> protects its key from pruning. Entries written
> before keys existed carry no key and are pruned as ordinary runs. Opening the linked response in
> the desktop app or the plugin is not implemented yet.

### Run history store

Original section, in full (the features page keeps retention, truncation, the secrets warning and `.gitignore` advice):

> `HistoryStore` (port in `core`) remembers past runs of a `.http` file: `record` (a `RunReport` plus the response of
> each request), `list` (newest first), `read`, `readResponse` and `latestResponse` (by request name). Only `<>` links use it
> today (see "Saved responses"); the CLI, desktop app and plugin do not record ordinary runs yet. `FileHistoryStore` (`adapters`)
> keeps it in `.http-history/<file name>/` next to the `.http` file; the folder is injectable
> (`FileHistoryStore.underRoot(dir)` for a shared root, or any `(Path) -> Path`).
>
> Layout: one folder per run, named by its id (`20261106T101500123Z-n0000-a1b2c3d4`: UTC time, a sequence number within the millisecond so equal-time runs keep their recorded order, random suffix; ids from before the sequence existed stay readable and sort first), holding `run.json`
> (version, id, timestamp, the report in the `JsonRunReport` format, and per response: status, headers, duration,
> body size, `bodyTruncated`) and `<index>.body` (raw bytes). `run.json` is written last; a folder without it is
> incomplete and ignored (removed after an hour). There is no shared index file, so two processes recording at once
> cannot lose each other's runs; all files are written atomically.
>
> - Retention: the newest 50 runs per file are kept (`maxRuns`); older ones are deleted when recording.
> - Bodies are stored in full up to 5 MB (`maxBodyBytes`); larger ones are cut, with `bodyTruncated = true` and the real
> size in `originalBodySize`.
> - Confinement: only the history folder is written. Body files are named by index, never by request name; the `.http`
> file name used as folder name is sanitized (separators, Windows-illegal characters, `.`/`..`, reserved names such
> as `CON`, trailing dots, length over 80); run ids are validated before use; symlinked entries are not followed.
> - Corrupt, foreign or newer-version entries are skipped with a warning, never fatal.
> - Secrets: stored responses are raw (tokens, cookies, personal data). On POSIX, folders are `rwx------` and files
> `rw-------` from creation (Windows uses the default ACLs). Add `.http-history/` to your `.gitignore` (this repository
> does) and never share the folder.

### Dynamic variables

- Library users and tests, original bullets:

> - Library users: `SystemEnvironmentVariables(setOf("NAME"))` from `adapters`; `core` callers that pass nothing
> see no variables (the `EnvironmentVariables` port).

> - For tests, `DynamicVariables(random, clock, env)` makes randomness, time and environment injectable.

- Random generator, original wording: `$uuid`, `$random.uuid` and the `$random.*` family use a cryptographically strong
  generator (`SecureRandom`), which is stronger than we know JetBrains to guarantee; this was our choice, not
  something taken from JetBrains. (`SecureRandom`, stronger than we know JetBrains to guarantee).

## Request chaining

- The extraction expressions are implemented by `ResponseExtractor` in `core`; original wording: `(via `ResponseExtractor` in `core`)` ... what assertions use.

## Command line

- `--fail-on-http-error`: The decision is `RunReport.from(outcomes, failOnHttpError = true)` in `core`.
- `--output FILE`: original wording: `It goes through the `OutputWriter` port (bytes) and `AtomicReportWriter`, which also implements `ReportWriter` for reports: parent` (the `OutputWriter` port writes bytes; `AtomicReportWriter` also implements `ReportWriter` for reports).

## Desktop app

Persistence details, original wording:

- Sizes and positions are device-independent pixels compared with AWT screen bounds: exact on single-DPI setups, not verified on mixed-DPI multi-monitor setups.
- Changes are saved in the background (debounced), saves are serialised so the newest state is always written last, and closing the window flushes them (waiting at most 2 s for a save in flight); a quit that bypasses the window close (macOS Cmd+Q, OS shutdown) can lose changes from the last 500 ms.
- The file is written atomically and owner-only on POSIX (a config folder created by the app is owner-only too); unknown fields are ignored.
- (then `.corrupt-1`, `.corrupt-2`, ...; an existing backup is never overwritten and the newest 5 are kept)
