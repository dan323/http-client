# http-client

A Postman-style HTTP client compatible with the JetBrains `.http` file format.

Hexagonal layout:

- `core/` — domain, application services (`HttpFileParser`, `VariableResolver`, `RequestRunner`) and ports. No third-party dependencies.
- `adapters/` — driven adapters: `JdkHttpExecutor`, `LocalFileReader`, `JsonEnvironmentRepository` (`http-client.env.json` + `.private.env.json`), `SessionFactory` (the composition root: assembles and closes executor, runner and session), `SystemEnvironmentVariables` (`{{$env.NAME}}`, opt-in allowlist; see [docs/features.md](docs/features.md)).
- `cli/` — command-line front end (`httpc`).
- `view-model/` — pure view logic (JSON tree/colour spans, status and size formatting, `RunController` run/session cache, `OutcomeDisplay` rows) shared by `desktop` and `intellij-plugin`.
- `desktop/` — standalone Compose Desktop app for Windows, macOS and Linux ([README](desktop/README.md)).
- `mock-server/` — local httpbin-style server used by `samples/` (`./gradlew :mock-server:run`).
- `samples/` — `.http` files for manual testing ([README](samples/README.md)).
- `intellij-plugin/` — IntelliJ plugin ([README](intellij-plugin/README.md)) (works in Community editions). Build with `./gradlew :intellij-plugin:buildPlugin`; set `ideaPath` in `gradle.properties` to a local IDE install.

Run tests: `./gradlew test`.

### Build quality reports

The build-only `quality-reports/` project (not shipped) aggregates reports of all modules except `intellij-plugin`:

- `./gradlew :quality-reports:qualityReports` runs the tests, then writes the aggregated test report (`quality-reports/build/reports/tests/test/index.html`) and the merged JaCoCo report (`quality-reports/build/reports/jacoco/testCodeCoverageReport/`, HTML plus `testCodeCoverageReport.xml`). `:quality-reports:coverageVerification` fails if merged coverage is below the floor set in `quality-reports/build.gradle.kts` (currently lines 95 %, branches 83 %, measured 95.4 % / 83.6 %). The floor only ever goes up.
- `./gradlew :core:pitest` runs PIT mutation testing on `core` (about a minute; HTML report in `core/build/reports/pitest/`). Measured mutation score 83 % (820 of 988 mutants killed); the build fails below 80 %.
- The IntelliJ plugin is excluded from JaCoCo (the IDE test runtime instruments classes itself and its tests need an IDE).

Documentation: [features](docs/features.md) · [plan](docs/PLAN.md) · [changelog](CHANGELOG.md)

## CLI

```
./gradlew :cli:run --args="requests.http --env dev --request login"
```

- `--env NAME` — use variables from `NAME` in `http-client.env.json` (next to the file).
- `--request X` — run only the request with `# @name X`, or the Nth request (1-based), plus any requests it chains on.
- `--list` — list the requests in the file and exit.
- `--report-json FILE` / `--report-junit FILE` — also write a run report (per-request status, timings, assertion results, totals) as JSON or JUnit XML; console output is unchanged; exit 3 means the report could not be written (see [features](docs/features.md#run-reports)).

`# @assert status == 200` lines are checked after each response; the CLI prints PASS/FAIL and exits 1 on any failure (see [features](docs/features.md#assertions)).

`./gradlew :cli:installDist` builds a standalone `httpc` launcher in `cli/build/install/httpc/bin`.
