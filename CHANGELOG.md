# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `RunReport` (T9): pure report model in `core` built from `RunOutcome`s (per-request status, duration, assertions, error; totals; overall pass/fail), `JUnitXmlReport` in `core` and `JsonRunReport` (export and parse) in `adapters`. The CLI gained `--report-json FILE` and `--report-junit FILE`; console output is unchanged. Reports: one JUnit `failure` per failed request, unique `N. name` testcase names, passing prerequisites omitted (JSON: `prerequisite` flag), JSON `version` field, atomic writes, option validation, and exit code 3 when a report cannot be written (T9 review fixes).
- Assertions (T5): `# @assert <expr> <op> [expected]` directives (`RequestTemplate.assertions`), `AssertionEvaluator` in `core`, results on `RunOutcome.Success.assertions`; the CLI prints PASS/FAIL per assertion and exits 1 when one fails. Example in `samples/assertions.http`. Unknown expression prefixes are invalid, numbers compare exactly (BigDecimal), `{{variables}}` are substituted, quoting for expressions with spaces and for empty/literal expected values, `matches` has a 2 s guard and dot-matches-all, a missing value fails every operator but `!exists`, `--request` asserts only the selected request, and the CLI summary counts assertions not run for failed requests. `RequestSession.runAll` gained an `assertFor` parameter and `RunOutcome.Failure.assertionsNotRun`.
- `ResponseExtractor` in `core` (T4): status, header, JSONPath, XPath (`body./x`, `body.xpath:`) and regex (`body.regex:`) lookups on a response, used by `{{name.response...}}` chaining and reusable by assertions. XPath and regex use the JDK only; DOCTYPE is rejected.
- Per-request executor options: `# @timeout`, `# @no-redirect` and `# @proxy host:port` settings are resolved into `HttpRequest.options` (`RequestOptions`) and honoured by `JdkHttpExecutor` (T2). Values are validated when the request is resolved (`Invalid @timeout`/`@proxy`/`@no-redirect`): positive whole timeouts with case-insensitive units and overflow checks, `@no-redirect [true|false]`, http-only proxies via the new `ProxyAddress` type (default port 80, no credentials). The timeout bounds the wait for the response, not body streaming. `JdkHttpExecutor` caches derived clients, inherits the injected client's configuration and is `AutoCloseable`.
- Parser: `@var = value` file variables (override environment values, may reference other variables) and `# @setting [value]` directives, exposed as `RequestTemplate.fileVariables` / `settings` and `HttpFile.variables` (T1).

### Fixed
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
