# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed
- Session robustness (R3): `RequestSession.runAll` rethrows `InterruptedException` (restoring the interrupt flag) instead of reporting it as a failure and sending the remaining requests; the session is safe for concurrent use (runs are serialised, `hasResponse` is lock-free); an invalid assertion (bad regex, non-numeric `<`, unknown operator) keeps its reason when a `{{variable}}` is used, with the resolved values left out. Refactors: redirect handling moved to `RedirectFollower`, option parsing to `RequestOptionsParser`, merged duplicated catch blocks, `VariableResolver.resolve` uses a lazily created shared `DynamicVariables.DEFAULT` instead of building a `SecureRandom` per call. Review fixes: with hidden values a bad-regex or missing-expected-value reason never echoes JDK/pattern/operator text (fixed `bad regex`); a caller queued on the session lock can be interrupted (`lockInterruptibly`, flag kept); `runAll` restores the interrupt flag itself when it catches a `CancellationException` caused by an `InterruptedException`; `RedirectFollower` takes an injectable clock.
- Executor safety (R4): `# @timeout` now bounds the whole exchange, headers and body, and cancels the request (previously a server that sent headers and stalled hung forever); the default client has a 30 s connect timeout (no default total timeout). Interrupting the calling thread cancels the in-flight request, restores the interrupt flag and throws `CancellationException`. Documented that `Host`/`Content-Length`/`Connection`/`Expect`/`Upgrade` headers are dropped by the JDK client. Verified (tests) that `Authorization`, `Proxy-Authorization` and `Cookie` are not re-sent on cross-host redirects, so no change was needed there.

### Added
- Cookie jar (T3): `CookieJar` in `core`, owned per `RequestSession`; `Set-Cookie` responses are replayed as `Cookie` on later matching requests of the same session, and `# @no-cookie-jar [true|false]` (`RequestOptions.useCookieJar`) opts a request out of both sending and storing. Pure Kotlin, so it behaves the same through every `JdkHttpExecutor` derived client. The session follows redirects hop by hop when the jar is used (limit 10, honors `@no-redirect`, cookies stored and `Cookie` recomputed per hop, credentials dropped cross-origin), so `Set-Cookie` on a 302 works. Lenient `Expires` parsing, overbroad/IP `Domain` rejection, Secure-from-http rejection, Max-Age hardening, cookie count/size caps.
- `RunReport` (T9): pure report model in `core` built from `RunOutcome`s (per-request status, duration, assertions, error; totals; overall pass/fail), `JUnitXmlReport` in `core` and `JsonRunReport` (export and parse) in `adapters`. The CLI gained `--report-json FILE` and `--report-junit FILE`; console output is unchanged. Reports: one JUnit `failure` per failed request, unique `N. name` testcase names, passing prerequisites omitted (JSON: `prerequisite` flag), JSON `version` field, atomic writes, option validation, and exit code 3 when a report cannot be written (T9 review fixes).
- Dynamic variables v2 (T6): `{{$random.integer(a, b)}}`, `$random.float`, `$random.alphabetic/alphanumeric/hexadecimal(n)`, `$random.email`, `$random.uuid`, `{{$randomInt from to}}`, offsets on `$timestamp` / `$isoTimestamp` (`1 d`, `-2 h`; units `ms s m h d w M y`) and `{{$env.NAME}}` / `{{$processEnv NAME}}` via the new `EnvironmentVariables` port (`SystemEnvironmentVariables` adapter, wired in cli, desktop and plugin). `$env` is opt-in: no variable is readable until allowed by name, with `--allow-env NAME[,NAME]` in the CLI and `-Dhttpclient.allowEnv=` / `HTTPC_ALLOW_ENV` in the desktop app and plugin; others fail with an error naming the variable and how to allow it. Randomness, clock and environment are injectable through `DynamicVariables`. Malformed arguments fail with `Invalid dynamic variable`; unknown ones stay `Undefined variable`. `$uuid` and `$random.*` default to `SecureRandom` (a seeded `Random` can be injected); `$random.float` never prints scientific notation; `random.integer`/`randomInt` handle the full Long range; a malformed dynamic variable in an `# @assert` only invalidates that assertion. Dynamic references may contain spaces only for `$timestamp`, `$isoTimestamp`, `$randomInt`, `$uuid`, `$random.*` and `$processEnv`; `$` must be followed by a letter and references never span lines, so `{{$ x}}`/`{{$x := 1}}` text is untouched. `RequestRunner.resolveText` replaces the public `dynamic` property. `RequestRunner` gained an optional `DynamicVariables`. Example in `samples/dynamic.http`.
- Assertions (T5): `# @assert <expr> <op> [expected]` directives (`RequestTemplate.assertions`), `AssertionEvaluator` in `core`, results on `RunOutcome.Success.assertions`; the CLI prints PASS/FAIL per assertion and exits 1 when one fails. Example in `samples/assertions.http`. Unknown expression prefixes are invalid, numbers compare exactly (BigDecimal), `{{variables}}` are substituted, quoting for expressions with spaces and for empty/literal expected values, `matches` has a 2 s guard and dot-matches-all, a missing value fails every operator but `!exists`, `--request` asserts only the selected request, and the CLI summary counts assertions not run for failed requests. `RequestSession.runAll` gained an `assertFor` parameter and `RunOutcome.Failure.assertionsNotRun`.
- `ResponseExtractor` in `core` (T4): status, header, JSONPath, XPath (`body./x`, `body.xpath:`) and regex (`body.regex:`) lookups on a response, used by `{{name.response...}}` chaining and reusable by assertions. XPath and regex use the JDK only; DOCTYPE is rejected.
- Per-request executor options: `# @timeout`, `# @no-redirect` and `# @proxy host:port` settings are resolved into `HttpRequest.options` (`RequestOptions`) and honoured by `JdkHttpExecutor` (T2). Values are validated when the request is resolved (`Invalid @timeout`/`@proxy`/`@no-redirect`): positive whole timeouts with case-insensitive units and overflow checks, `@no-redirect [true|false]`, http-only proxies via the new `ProxyAddress` type (default port 80, no credentials). The timeout bounds the whole exchange (headers and body; see Fixed). `JdkHttpExecutor` caches derived clients, inherits the injected client's configuration and is `AutoCloseable`.
- Parser: `@var = value` file variables (override environment values, may reference other variables) and `# @setting [value]` directives, exposed as `RequestTemplate.fileVariables` / `settings` and `HttpFile.variables` (T1).

### Fixed
- R1 dependency scan and variable resolution: `withDependencies` follows `{{name.response...}}` through used file variables (transitively), `# @assert` expressions and setting values, and finds request names containing dots (split on the first `.response.`); a cyclic file variable fails with `Circular variable reference: a -> b -> a` instead of sending a literal `{{a}}`, and one resolve is bounded to 10,000 expansions / 100 levels; environment, response and dynamic values containing `{{` are no longer re-interpreted (only file variable values are expanded; so an environment value such as `"api": "{{host}}/v1"` is now sent literally, compose with file variables instead). A file variable's expansion is cached per resolve call unless it uses dynamic values, so the 10,000 limit counts distinct expansions, not uses; `@host = {{host}}/api` without an environment `host` reports `Undefined variable: host`, and cycle chains list only the cycle. `VariableResolver.withFileVariables` is replaced by a `fileVariables` parameter of `resolve`.
- Lexer and parser agreement (R2): `HttpLineLexer` no longer treats `@var = value` lines as the request URL (the gutter icon now lands on the real request line); lexer and parser share `HttpLineSyntax` (one method set and request-line classifier); lexer modes are the `HttpLineLexer.Mode` enum. An unknown method such as `GTE http://x` and a header line without `:` are `HttpParseException`s with a 1-based line number (a bare URL is still a GET). `> {% ... %}`, `> file` and `<> file` lines are no longer sent as body: they are skipped and reported in the new `HttpFile.warnings` (printed by the CLI on stderr). Only `< path` (with a space) is a file reference, so `<html>` bodies are plain text.
  Review follow-up: `GRAPHQL`, `WEBSOCKET` and `GRPC` are accepted methods and a one-edit typo gets a "did you mean" hint; a lone `GET` with no URL is a parse error; indented `?a=1` / `&b=2` lines extend the URL and indented lines without `:` fold into the previous header value; the lexer recognises handlers directly after headers; `> ` / `<> ` lines only count as handlers at the end of the block (earlier ones are body text); `%}` inside a quoted string does not end a handler script and a script that never closes warns; `<@ path` is a file body (variables in the file are not substituted, with a warning); the lexer ignores `\r` of CRLF line ends; the desktop request list shows the parse error.
- T6 review fixes: `$random.float` rejects equal endpoints (range is from-inclusive, to-exclusive); `EnvironmentVariables.isAllowed` denies by default; `$env` lookup is case-insensitive on Windows (allowlist stays exact); setting validation errors and assertion results no longer echo resolved `{{variables}}` (a failing assertion with substitutions reports `failed (expected value contains substitutions, not shown)`); the variable regex is linear on unclosed `{{$...` followed by long whitespace, never spans lines before `(`, and `{{$uuid }}` matches a `$uuid` override.
- IntelliJ plugin: stop bundling kotlinx-serialization in the plugin zip (the IDE provides it), avoiding a class conflict.

### Changed
- New `view-model` module (T12): UI-toolkit-free JSON tree flattening (`flattenJson`), JSON colour spans (`jsonSpans`), status class/text and size/duration labels, moved out of `desktop` and `intellij-plugin` and shared by both. The plugin now shows the status reason phrase and `1.5 s`-style durations. Desktop behaviour change: durations were always `N ms` (e.g. `1500 ms`) and are now `1.5 s` from 1 s up (`2m 5s` from 1 min), and size/duration numbers are formatted with `Locale.ROOT` (always `.` decimal separator) instead of the system locale; sizes gain a GB tier and negative sizes/durations show as 0. The plugin zip still excludes kotlinx-serialization, now enforced by the `verifyNoBundledSerialization` Gradle task (runs with `check` and after `buildPlugin`).
- IntelliJ plugin sandbox verified to load without errors in IDEA 2026.1.5 (T21); GUI behavior still unverified, see `intellij-plugin/README.md`.

## [0.2.0] - 2026-11-03

### Added
- Desktop response viewer: status badge, Body/Headers tabs, collapsible syntax-colored JSON tree, Raw view and Copy.
- Desktop: syntax highlighting in the editor, Ctrl/Cmd+S and Ctrl/Cmd+Enter shortcuts, opening a file from a launch argument, file-dialog errors shown in the UI, and headless UI tests.
- `HttpLineLexer` in `core`, shared by the desktop editor and the IntelliJ plugin lexer.
- `mock-server` module (httpbin-style, JDK only) and `samples/` `.http` files that run against it offline; `SamplesTest` keeps them working.
- Desktop app (`desktop` module, Compose Multiplatform): open/edit/save `.http` files, environment picker, send one or all requests, response viewer; DMG/MSI/DEB packaging. READMEs for `desktop` and `intellij-plugin`.
- `RequestSession.runAll` and `RunOutcome`, shared by the CLI, plugin and desktop app.
- IntelliJ plugin (`intellij-plugin` module): `.http` file type, "Run HTTP Request" (Ctrl+Alt+Enter) for the request at the caret, environment picker, and an "HTTP Response" tool window.
- IntelliJ plugin: syntax highlighting, gutter run icons, pretty-printed JSON responses.
- `RequestTemplate.startLine`; dependency selection (`withDependencies`) moved from the CLI into `core`.
- `--request` now also runs the requests the selected one chains on.
- Request chaining: later requests can reference earlier named responses with
  `{{name.response.body.$.path}}`, `{{name.response.body.*}}`,
  `{{name.response.headers.Header}}` and `{{name.response.status}}`.
  Adds the `JsonExtractor` port, the `KotlinxJsonExtractor` adapter and `RequestSession`.
- Dynamic variables: `{{$uuid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`.
- End-to-end CLI tests against a local in-process HTTP server.
- Command-line front end (`cli` module, `httpc`) with `--env`, `--request` and `--list`.
- Gradle wrapper.
- `CLAUDE.md` guidance for Claude Code.

### Changed
- Added `docs/PLAN.md`: feature list, deduplicated tasks and a 3-lane timeline.
- CLI logic moved from `main` into a testable `run(args, out, err): Int`.
- README documents the CLI and links to the feature docs.

## [0.1.0]

### Added
- Hexagonal scaffold: `core` (domain, application services, ports) and `adapters`.
- Parser for the JetBrains `.http` format (`###` separators, `# @name`, comments, `< path` file bodies).
- `{{variable}}` resolution that fails on undefined variables.
- JDK `java.net.http` executor, local file reader, and JSON environment repository
  (`http-client.env.json` plus `http-client.private.env.json`).
