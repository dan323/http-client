# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Codex, Copilot, etc.) when working with code in this repository.

## Overview

A Postman-style HTTP client compatible with the JetBrains `.http` file format. Kotlin (JVM 21 toolchain), Gradle Kotlin DSL multi-module build. Modules: `core`, `adapters`, front ends `cli`, `desktop` (Compose Desktop) and `intellij-plugin`, plus `view-model` (UI-toolkit-free shared view logic: JSON tree/spans, status and size formatting) and `mock-server` (httpbin-style server for `samples/`), and the build-only `quality-reports` project (aggregated test and coverage reports, not shipped). Each module has its own README where relevant; see `docs/features.md` for the `.http` features.

## Commands

- Run all tests: `./gradlew test`
- One module: `./gradlew :core:test`, `:adapters:test`, `:view-model:test`, `:cli:test`, `:desktop:test`, `:mock-server:test`, `:intellij-plugin:test` (the plugin needs an IDE, see its README)
- Single test class/method: `./gradlew :core:test --tests "com.dan323.httpclient.core.HttpFileParserTest"` (append `.methodName` for one method)
- Build the IntelliJ plugin: `./gradlew :intellij-plugin:buildPlugin` (uses `ideaPath` if it exists, else downloads `ideaVersion`)
- Build quality reports (build-only `quality-reports` project, not shipped; excludes `intellij-plugin`): `./gradlew :quality-reports:qualityReports` runs the tests and writes the aggregated JUnit HTML report (`quality-reports/build/reports/tests/test/aggregated-results/index.html`) and the merged JaCoCo report (`quality-reports/build/reports/jacoco/testCodeCoverageReport/`, HTML + `testCodeCoverageReport.xml` for SonarCloud), then `:quality-reports:coverageVerification` fails if merged coverage drops below the floor (lines 95 %, branches 83 %, in `quality-reports/build.gradle.kts`; raise it, never lower it). Mutation testing on `core` only: `./gradlew :core:pitest` (~1 minute, report in `core/build/reports/pitest/`, threshold 80 %, measured 83 %). PIT runs in CI on a schedule / manually, not on every push.
- Dependency and plugin versions live in `gradle/libs.versions.toml`

- Quality reports: `./gradlew :quality-reports:qualityReports` (aggregated test report, merged JaCoCo report, and the coverage floor, which must never decrease; excludes `intellij-plugin`); `./gradlew :core:pitest` (PIT mutation testing on `core`, fails below 80 %; CI runs it weekly).

Tests use `kotlin("test")` on JUnit Platform. There is no linter configured.

## Architecture

Hexagonal layout; dependencies point inward only.

- `core/` — the hexagon. Must stay free of third-party runtime dependencies.
  - `domain/Model.kt`: data types (`HttpFile`, `RequestTemplate`, `HttpRequest`, `HttpResponse`, `Body`, `Environment`, ...). A `RequestTemplate` holds unresolved `{{var}}` text; an `HttpRequest` is the resolved, ready-to-send form.
  - `port/in/`: use-case interfaces (`ParseHttpFile`, `RunRequest`) that front ends call.
  - `port/out/`: interfaces the core needs from the outside (`HttpExecutor`, `FileReader` for `< path` bodies, `EnvironmentRepository`, `EnvironmentLocator` (which env file applies to a `.http` file), `EnvironmentVariables` for `$env`, `ReportWriter`, `JsonExtractor`).
  - `application/`: implementations (`HttpFileParser`, `VariableResolver`, `RequestRunner`). `RequestRunner` resolves variables in URL, headers and body (later variable maps override earlier ones), reads file bodies through `FileReader`, then delegates to `HttpExecutor`.
  - `application/` also holds `RequestSession` (runs requests in order and resolves `{{name.response...}}` chaining), `withDependencies`, and `HttpLineLexer` (shared editor tokenizer).
- `adapters/` — driven adapters implementing the outbound ports: `JdkHttpExecutor` (JDK `java.net.http`), `LocalFileReader` confined by `FileAccessPolicy`, `JsonEnvironmentRepository` (reads `http-client.env.json` plus `.private.env.json`, via kotlinx-serialization), `UpwardEnvironmentLocator`, `SystemEnvironmentVariables`, `AtomicReportWriter`, `JUnitXmlReport`, `JsonRunReport`. Depends on `core` via `api`.
  - `SessionFactory` is the composition root: front ends get a `RequestSession` from it (it owns and closes the executor) instead of wiring executor, runner and reader themselves.
- `view-model/` — UI-toolkit-free logic shared by `desktop` and `intellij-plugin`: `RunController` (request at the caret, run with dependencies, session cache per file/environment/variables), `OutcomeDisplay` (`RunOutcome` to status class, header and body rows), JSON tree and spans, status/size formatting. Depends on `core` only.
- Front ends (`cli`, `desktop`, `intellij-plugin`) hold no wiring or run logic of their own beyond UI and argument handling; environment lookup is always through `EnvironmentLocator` and an explicit `EnvironmentChoice` (`None` / `Named`).

New I/O capabilities belong as a port in `core/port/out` with its implementation in `adapters`, not as a dependency of `core`.
