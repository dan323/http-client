# http-client

A Postman-style HTTP client compatible with the JetBrains `.http` file format.

Hexagonal layout:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`).
- `intellij-plugin/` — (planned) IDE front end.
- `desktop/` — (planned) standalone front end.

Run tests: `gradle test`.
