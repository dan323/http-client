# http-client

A Postman-style HTTP client compatible with the JetBrains `.http` file format.

Hexagonal layout:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`).
- `cli/` — command-line front end (`httpc`).
- `desktop/` — standalone Compose Desktop app for Windows, macOS and Linux ([README](desktop/README.md)).
- `mock-server/` — local httpbin-style server used by `samples/` (`./gradlew :mock-server:run`).
- `samples/` — `.http` files for manual testing ([README](samples/README.md)).
- `intellij-plugin/` — IntelliJ plugin ([README](intellij-plugin/README.md)) (works in Community editions). Build with `./gradlew :intellij-plugin:buildPlugin`; set `ideaPath` in `gradle.properties` to a local IDE install.

Run tests: `./gradlew test`.

Documentation: [features](docs/features.md) · [plan](docs/PLAN.md) · [changelog](CHANGELOG.md)

## CLI

```
./gradlew :cli:run --args="requests.http --env dev --request login"
```

- `--env NAME` — use variables from `NAME` in `http-client.env.json` (next to the file).
- `--request X` — run only the request with `# @name X`, or the Nth request (1-based), plus any requests it chains on.
- `--list` — list the requests in the file and exit.

`./gradlew :cli:installDist` builds a standalone `httpc` launcher in `cli/build/install/httpc/bin`.
