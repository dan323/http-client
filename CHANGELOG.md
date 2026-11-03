# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `ResponseExtractor` in `core` (T4): status, header, JSONPath, XPath (`body./x`, `body.xpath:`) and regex (`body.regex:`) lookups on a response, used by `{{name.response...}}` chaining and reusable by assertions. XPath and regex use the JDK only; DOCTYPE is rejected.
- Parser: `@var = value` file variables (override environment values, may reference other variables) and `# @setting [value]` directives, exposed as `RequestTemplate.fileVariables` / `settings` and `HttpFile.variables` (T1).

### Fixed
- IntelliJ plugin: stop bundling kotlinx-serialization in the plugin zip (the IDE provides it), avoiding a class conflict.

### Changed
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
