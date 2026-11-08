# http-client

A Postman-style HTTP client that implements a practical subset of the JetBrains `.http` format, with additional testing and execution features. See [how it differs from the JetBrains HTTP Client](docs/jetbrains-compatibility.md).

Hexagonal layout:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`) with `UpwardEnvironmentLocator` (nearest env file, searching upward), `FileAccessPolicy` (confines `< path` bodies), report writers (`AtomicReportWriter`, `JUnitXmlReport`, `JsonRunReport`), `FileHistoryStore` (run history in `.http-history/`; raw responses, keep out of version control), `SessionFactory` (the composition root: assembles and closes executor, runner and session), `SystemEnvironmentVariables` (`{{$env.NAME}}`, opt-in allowlist; see [docs/features.md](docs/features.md)).
- `cli/` — command-line front end (`httpc`).
- `view-model/` — pure view logic (JSON tree/colour spans, status and size formatting, `RunController` run/session cache, `OutcomeDisplay` rows) shared by `desktop` and `intellij-plugin`.
- `desktop/` — standalone Compose Desktop app for Windows, macOS and Linux ([README](desktop/README.md)).
- `quality-reports/` — build-only project for aggregated test and coverage reports (below).
- `mock-server/` — local httpbin-style server used by `samples/` (`./gradlew :mock-server:run`).
- `samples/` — `.http` files for manual testing ([README](samples/README.md)).
- `examples/` — a CI smoke-test `.http` file with its environment file (see [getting started](docs/getting-started.md)).
- `intellij-plugin/` — IntelliJ plugin ([README](intellij-plugin/README.md)) (works in Community editions). Build with `./gradlew :intellij-plugin:buildPlugin`; set `ideaPath` in `gradle.properties` to a local IDE install.

Run tests: `./gradlew test`.

### Build quality reports

The build-only `quality-reports/` project (not shipped) aggregates reports of all modules except `intellij-plugin`:

- `./gradlew :quality-reports:qualityReports` runs the tests, then writes the aggregated test report (`quality-reports/build/reports/tests/test/aggregated-results/index.html`) and the merged JaCoCo report (`quality-reports/build/reports/jacoco/testCodeCoverageReport/`, HTML plus `testCodeCoverageReport.xml`). `:quality-reports:coverageVerification` fails if merged coverage is below the floor set in `quality-reports/build.gradle.kts` (currently lines 95 %, branches 83 %, measured 95.4 % / 83.6 %). The floor only ever goes up.
- `./gradlew :core:pitest` runs PIT mutation testing on `core` (about a minute; HTML report in `core/build/reports/pitest/`). Measured mutation score 83 % (820 of 988 mutants killed); the build fails below 80 %.
- The IntelliJ plugin is excluded from JaCoCo (the IDE test runtime instruments classes itself and its tests need an IDE).

Documentation: [getting started](docs/getting-started.md) · [features](docs/features.md) · [design notes](docs/design-notes.md) · [differences from JetBrains](docs/jetbrains-compatibility.md) · [plan](docs/PLAN.md) · [changelog](CHANGELOG.md)

## CLI

```
./gradlew :cli:run --args="requests.http --env dev --request login"
```

- `--env NAME` — use variables from `NAME` in the nearest `http-client.env.json` (searching upward from the file; see [features](docs/features.md#environments)). Without it no environment is used.
- `--request X` — run only the request with `# @name X`, or the Nth request (1-based), plus any requests it chains on.
- `--allow-env NAME[,NAME]` — let `{{$env.NAME}}` read these OS variables (none by default).
- `--allow-file-root DIR` — also let `< path` bodies be read from `DIR` (by default only the `.http` file's folder).
- `--list` — list the requests in the file and exit.
- `--report-json FILE` / `--report-junit FILE` — also write a run report (per-request status, timings, assertion results, totals) as JSON or JUnit XML; console output is unchanged; a report path must differ from the input file (see [features](docs/features.md#run-reports)).

- `--quiet` — no per-request output on stdout; failed assertions and errors still go to stderr.
- `--fail-on-http-error` — exit `1` when a selected request gets a 4xx/5xx response, even if its assertions pass (printed to stderr, also with `--quiet`; prerequisites run only for chaining are not judged). Without it an HTTP error status does not fail the run.
- `--output FILE` — write the raw response body (byte for byte, so binaries work; text keeps its original charset; gzip/br bodies stay compressed, as received) of the single selected request to `FILE` (needs `--request` when the file holds several requests; owner-only file).
- `--var NAME=VALUE` — set a variable (repeatable); it overrides file and environment variables.
- `--timeout DURATION` — total time limit for requests without `# @timeout` (`30`, `500ms`, `45s`, `2m`; default 60 s).

After a run a summary line such as `3 requests, 2 passed, 1 failed in 1.2s` is printed to stderr.

Exit codes: `0` success, `1` a request, assertion or setup failed, `2` bad arguments, `3` the run passed but a report or output file could not be written.

`# @assert status == 200` lines are checked after each response; the CLI prints PASS/FAIL and exits 1 on any failure (see [features](docs/features.md#assertions)).

`./gradlew :cli:installDist` builds a standalone `httpc` launcher in `cli/build/install/httpc/bin`; `./gradlew :cli:distZip` (or `distTar`) builds the downloadable distribution that releases attach. Install steps for release assets: [getting started](docs/getting-started.md).
