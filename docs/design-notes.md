# Design notes

How the behaviour described in [features.md](features.md) is implemented, and why. Only the sections that have internals worth keeping are covered; they use the same names as there. Class names are from `core` and `adapters` (see [AGENTS.md](../AGENTS.md)).

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

## `.http` files

`< path` and `<@ path` reads go through `FileAccessPolicy`: the path is normalised (`..`), symlinks are resolved and the result must lie in the `.http` file's folder or an allowed root. The file is opened without following a final symlink and the real path is checked again. The JDK has no `openat`, so one race remains: someone with write access inside an allowed folder can swap a parent directory for a symlink between the check and the open.

## Variables

### Editor diagnostics (analysis)

`VariableAnalysis` (`core`, pure and IO-free) works on the text and the names of the environment variables only; values never enter the analysis (pass the union of all environments for a lenient editor, nothing for "no environment").

- `analyze(text, environmentNames)` returns `Diagnostic(code, severity, message, range)`; a `SourceRange` is in UTF-16 chars (offset, length, 0-based line and column).
- `knownVariablesAt(offset)` lists file variables above, environment variables, `name.response.` prefixes of earlier requests and the dynamic variable templates. `findUsages`, `findVariableUsages`, `findRequestUsages` and `occurrenceAt` return ranges covering exactly the name (for rename).
- It shares the reference pattern and lookup order with `VariableResolver`; a consistency test runs `samples/` and compares with run time. A file variable beats the environment in request text, and in a value only when defined above it.
- A file that does not parse still gets analysed: each rejected line becomes a `parse-error` and is left out without shifting the other offsets. A file with more than 50 bad lines reports only parse errors. The request that failed has no `name.response.` prefix and its references are not scanned.
- A response reference inside a file variable value is only checked for an existing request name, because the request that uses the variable is not known. Duplicate request names resolve to the latest response at run time. Analysis is linear in the text size.

### Request settings

The parser stores settings on `RequestTemplate.settings`; the executor honours them through `HttpRequest.options`. `JdkHttpExecutor` always sends with `Redirect.NEVER` clients and follows redirects itself with `RedirectFollower`, so credential stripping does not depend on the JDK version. It reuses one derived client per proxy and copies the injected client's connect timeout, SSL, authenticator, cookie handler, executor and HTTP version (request priority cannot be copied). Interrupting the calling thread cancels the in-flight request with a `CancellationException` and keeps the interrupt flag.

### Cookie jar

- `RequestSession` owns the in-memory `CookieJar` (`core`). In the desktop app and plugin the shared `RunController` keeps one session per file, environment and loaded variables.
- The session follows redirects one hop at a time and tells the executor not to follow any, which is why `Set-Cookie` on a `302` is stored; the credential-header handling is described under Request settings.
- `Path` is matched on the raw, undecoded path. `Max-Age` takes digits with an optional `-` (`+5` or text is ignored; extreme values saturate). `Expires` uses lenient RFC 6265 parsing (`21 Oct 2015`, `21-Oct-2015`, 2-digit years, asctime).
- `@no-cookie-jar` cannot disable a `CookieHandler` configured on an `HttpClient` injected into `JdkHttpExecutor`; that handler still sends and stores on its own.
- Public Suffix List: `core` has no third-party dependencies, so the list is a classpath resource, `core/src/main/resources/com/dan323/httpclient/core/public_suffix_list.txt` (ICANN and private sections, rules only; the first line records the list version and commit), read by `PublicSuffixList`. Wildcard and exception rules are supported and a name with no matching rule counts as a one-label suffix, as in the PSL algorithm. To update: download `https://publicsuffix.org/list/public_suffix_list.dat`, drop comment lines (`//`), blank lines and anything after whitespace, put a header line with the new `VERSION` and `COMMIT` on top, replace the file and run `./gradlew :core:test`.

### Assertions

`RequestSession.runAll` evaluates assertions and attaches one `AssertionResult` (passed, expected, actual, message) to each `RunOutcome.Success`; a request that throws reports `RunOutcome.Failure.assertionsNotRun`. `runChained` (single-request runs) evaluates only the requested request, and reused dependencies are not re-asserted. A session is safe for concurrent use: runs are serialised, and interrupting the running thread stops `runAll` without sending the queued requests. Numbers are `BigDecimal` (`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d{1,4})?`).

### Run reports

`RunReport.from(outcomes, failOnHttpError)` (`core`) is pure; `RunReport.passed` decides pass or fail. Exporters are in `adapters`: `JUnitXmlReport` (plain string building with XML escaping; illegal XML characters are dropped; tabs and line breaks in attributes become character references) and `JsonRunReport` (kotlinx-serialization; `parse` reads a report back, rejects a newer `version` and recomputes `passed` and `totals`). Files go through the `ReportWriter` port, implemented by `AtomicReportWriter` (temp file and move, independently per report; a replaced report keeps its permissions).

### Saved responses

- Ports: `ResponseSaver` (`core`), `LocalResponseSaver` (`adapters`). The path is confined by the same `FileAccessPolicy` as `< path` bodies. Results are `RunOutcome.Success.savedTo` and `saveError`.
- Publishing: the temp file (random name, owner-only) is written in the final, already verified folder. `>>!` is one atomic rename inside that folder. `>>` hard-links the finished temp file to the first free name (the link fails if the name exists, so nothing is clobbered and no empty or partial file is visible) and then removes the temp name; without hard links it uses a plain move without replace. Concurrent `>>` saves of one name end up in different files.
- Race handling: right before publishing, the folder's real path must still be the verified one and the temp file must be in it. If the folder was swapped for a symlink, the temp name does not exist at the new location, so the rename or link fails and nothing outside is touched. The residual race needs an attacker who can write inside an allowed folder, observe the random temp name and swap the folder between the last check and the syscall while placing a file of that name at the new location. It is detected afterwards: a `>>` file is removed, a `>>!` replacement cannot be restored and the error says so.
- `RequestSession.run` alone does not save or link; `runAll` and `runChained` apply `>>`, `>>!` and `<>` once per request. Cancellation inside a save or link is rethrown (it stops the session) and is not a save error; runtime link failures become `linkError`.
- `<>` links: `RequestTemplate.responseLinks` keeps the text, but the path is not used. After a run, `HistoryStore.recordLinked` stores the response under the request's key (its `# @name`, or its start line); `ResponseLinks.latest(store, httpFile, request)` returns it via `HistoryStore.latestLinked`. Recording is opt-in through `SessionFactory(history = ...)`, which the CLI, plugin and desktop app pass as a `FileHistoryStore`. Pruning keeps the newest 50 runs plus the newest readable record of every link key, at most one extra run folder per linked request; a record whose body cannot be read does not protect its key. Entries written before keys existed are ordinary runs.

### Run history store

The desktop app records ordinary runs through the same store, only when the user turns **Record history** on (`DesktopSettings.recordHistory`, off by default because stored responses are raw): `AppState` calls `HistoryStore.record` after a run with the `RunReport` and one response (or null) per outcome, and a store failure only sets a message. The History panel reads through `HistoryState`; the comparison of two stored responses is `diffResponses` in `view-model`, a line diff over the lines left after the common start and end are removed, capped so that it stays cheap (`ResponseDiffLimits`).

`HistoryStore` (port in `core`: `record`, `list`, `read`, `readResponse`, `latestResponse` by request name, `recordLinked`, `latestLinked`) is implemented by `FileHistoryStore` (`adapters`) in `.http-history/<file name>/` next to the `.http` file; the folder is injectable (`FileHistoryStore.underRoot(dir)` or any `(Path) -> Path`).

- Layout: one folder per run, named by its id, e.g. `20261106T101500123Z-n0000-a1b2c3d4` (UTC time, a sequence number within the millisecond so equal-time runs keep their order, random suffix; older ids without the sequence stay readable and sort first). It holds `run.json` (version, id, timestamp, the report in `JsonRunReport` format, per response status, headers, duration, body size, `bodyTruncated`, `originalBodySize`) and `<index>.body` (raw bytes).
- Atomicity: all files are written atomically and `run.json` last; a folder without it is incomplete, ignored and removed after an hour. There is no shared index file, so two processes recording at once cannot lose each other's runs.
- Confinement: only the history folder is written. Body files are named by index, never by request name. The `.http` file name used as folder name is sanitised (separators, Windows-illegal characters, `.`/`..`, reserved names such as `CON`, trailing dots, length over 80), run ids are validated before use and symlinked entries are not followed.
- Limits: `maxRuns` 50, `maxBodyBytes` 5 MB. Corrupt, foreign or newer-version entries are skipped with a warning.
- Permissions: on POSIX folders are `rwx------` and files `rw-------` from creation; Windows uses the default ACLs.

### Dynamic variables

`DynamicVariables(random, clock, env)` makes randomness, time and environment injectable for tests. `$env` reads go through the `EnvironmentVariables` port; `core` callers that pass nothing see no variables, and library users pass `SystemEnvironmentVariables(setOf("NAME"))` from `adapters`. Randomness is `SecureRandom`.

## Request chaining

Extraction (JSONPath subset, header, XPath, regex) is `ResponseExtractor` in `core`, shared by chaining and assertions. XPath evaluation cannot be interrupted, so its number is capped instead: at most 4 at a time in the whole process, and timed-out ones count until they really end.

## Command line

`--output` goes through the `OutputWriter` port (bytes), implemented by `AtomicReportWriter` together with `ReportWriter`. `--fail-on-http-error` is `RunReport.from(outcomes, failOnHttpError = true)`. The resolver prefers file variables over the variable map, so the CLI removes names overridden by `--var` from the file variables first.

## Desktop app

Persistence is `SettingsStore` (`core`) with `JsonSettingsStore` and `ConfigDirLocator` (`adapters`).

- Saves are debounced, serialised so the newest state is written last, and `close` waits up to 2 s for one in flight. The file is written atomically; unknown fields are ignored.
- A corrupt, empty, foreign or newer-version file is backed up before the next save as `settings.json.corrupt`, then `.corrupt-1`, `.corrupt-2`, ...; an existing backup is never overwritten and the newest 5 are kept. After an I/O error on read, saving is skipped (reported once) until the file can be read; then stored and in-memory settings are merged.
- Recent files: 10 entries; files missing at start are hidden but stay stored, and a vanished one is removed when clicked. Environments are remembered for the 50 most recently used files.
- Window: fullscreen is stored as the maximized flag and the last normal size is kept; the bogus (-32000, -32000) position Windows reports when minimized is ignored; with no screen information the position is dropped. Sizes and positions are device-independent pixels compared with AWT screen bounds: exact on single-DPI setups, not verified on mixed-DPI multi-monitor setups.
