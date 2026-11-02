# http-client

A Postman-style HTTP client compatible with the JetBrains `.http` file format.

Hexagonal layout:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`).
- `cli/` — command-line front end (`httpc`).
- `intellij-plugin/` — (planned) IDE front end.
- `desktop/` — (planned) standalone front end.

Run tests: `./gradlew test`.

Documentation: [features](docs/features.md) · [changelog](CHANGELOG.md)

## CLI

```
./gradlew :cli:run --args="requests.http --env dev --request login"
```

- `--env NAME` — use variables from `NAME` in `http-client.env.json` (next to the file).
- `--request X` — run only the request with `# @name X`, or the Nth request (1-based).
- `--list` — list the requests in the file and exit.

`./gradlew :cli:installDist` builds a standalone `httpc` launcher in `cli/build/install/httpc/bin`.
