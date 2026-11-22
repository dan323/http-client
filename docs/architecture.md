# Architecture and modules

The project is hexagonal: dependencies point inward only (the rules are in [AGENTS.md](../AGENTS.md)). The modules:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`) and `JsonEnvironmentFileStore` (edits those files without losing what it does not show) with `UpwardEnvironmentLocator` (nearest env file, searching upward), `FileAccessPolicy` (confines `< path` bodies), report writers (`AtomicReportWriter`, `JUnitXmlReport`, `JsonRunReport`), `FileHistoryStore` (run history in `.http-history/`; raw responses, keep out of version control), `SessionFactory` (the composition root: assembles and closes executor, runner and session), `SystemEnvironmentVariables` (`{{$env.NAME}}`, opt-in allowlist; see [docs/features.md](features.md)).
- `cli/` — command-line front end (`httpc`).
- `view-model/` — pure view logic (JSON tree/colour spans, status and size formatting, `RunController` run/session cache, `OutcomeDisplay` rows) shared by `desktop` and `intellij-plugin`.
- `desktop/` — standalone Compose Desktop app for Windows, macOS and Linux ([README](../desktop/README.md)).
- `quality-reports/` — build-only project for aggregated test and coverage reports ([quality reports](quality.md)).
- `mock-server/` — local httpbin-style server used by `samples/` (`./gradlew :mock-server:run`).
- `samples/` — `.http` files for manual testing ([README](../samples/README.md)).
- `examples/` — a CI smoke-test `.http` file with its environment file (see [getting started](getting-started.md)).
- `agent-skill/` — a Claude Code plugin with a skill that teaches coding agents the `.http` format and `httpc` ([README](../agent-skill/README.md)).
- `intellij-plugin/` — IntelliJ plugin ([README](../intellij-plugin/README.md)) (works in Community editions). Build with `./gradlew :intellij-plugin:buildPlugin`; set `ideaPath` in `gradle.properties` to a local IDE install.
- `mcp-server/` — MCP server over stdio (`httpc-mcp`) for coding agents ([README](../mcp-server/README.md), [safety model](mcp-safety-model.md)).
