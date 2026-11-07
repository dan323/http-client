# AGENTS.md

A Postman-style HTTP client compatible with the JetBrains `.http` file format. Kotlin (JVM 21), Gradle Kotlin DSL multi-module build. See `docs/features.md` for the `.http` features and each module's README for details.

## Modules

- `core`: the domain and use cases. No third-party runtime dependencies.
- `adapters`: implementations of the ports `core` needs (HTTP via the JDK, files, environments, reports). Depends on `core`.
- `view-model`: UI-toolkit-free logic shared by `desktop` and `intellij-plugin`. Depends on `core` only.
- Front ends: `cli`, `desktop` (Compose Desktop), `intellij-plugin`.
- `mock-server` (httpbin-style server for `samples/`) and `quality-reports` (build-only, not shipped).

## Architecture rules

Hexagonal; dependencies point inward only.

- `core` never depends on a third-party library. New I/O goes in as a port in `core/port/out`, implemented in `adapters`.
- `SessionFactory` (in `adapters`) is the composition root. Front ends get a `RequestSession` from it and do not wire executors, runners or readers themselves.
- Front ends contain only UI and argument handling. Environment lookup always goes through `EnvironmentLocator` with an explicit `EnvironmentChoice` (`None` / `Named`).
- A `RequestTemplate` holds unresolved `{{var}}` text. An `HttpRequest` is the resolved, ready-to-send form.

## Commands

- All tests: `./gradlew test`. One module: `./gradlew :<module>:test` (`:intellij-plugin:test` needs an IDE, see its README).
- One test: `./gradlew :core:test --tests "com.dan323.httpclient.core.HttpFileParserTest"` (append `.methodName` for a method).
- IntelliJ plugin: `./gradlew :intellij-plugin:buildPlugin`.
- Coverage and reports: `./gradlew :quality-reports:qualityReports`. Fails if merged coverage drops below the floor in `quality-reports/build.gradle.kts` (lines 95 %, branches 83 %). Raise the floor, never lower it.
- Mutation tests on `core`: `./gradlew :core:pitest` (fails below 80 %; CI runs it weekly, not on every push).
- Dependency versions are in `gradle/libs.versions.toml`.

Tests use `kotlin("test")` on JUnit Platform. There is no linter.

## Workflow

- `master` is protected: work on a branch and open a PR. The checks `test` (ubuntu, windows, macos), `plugin` and `quality` must pass.
- Run the full tests locally before pushing.
