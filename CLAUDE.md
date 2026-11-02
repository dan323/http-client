# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A Postman-style HTTP client compatible with the JetBrains `.http` file format. Kotlin (JVM 21 toolchain), Gradle Kotlin DSL multi-module build. Front ends (`intellij-plugin/`, `desktop/`) are planned but not yet included in `settings.gradle.kts`; add them there when built.

## Commands

- Run all tests: `gradle test`
- One module: `gradle :core:test` or `gradle :adapters:test`
- Single test class/method: `gradle :core:test --tests "com.dan323.httpclient.core.HttpFileParserTest"` (append `.methodName` for one method)

Tests use `kotlin("test")` on JUnit Platform. There is no linter configured.

## Architecture

Hexagonal layout; dependencies point inward only.

- `core/` — the hexagon. Must stay free of third-party runtime dependencies.
  - `domain/Model.kt`: data types (`HttpFile`, `RequestTemplate`, `HttpRequest`, `HttpResponse`, `Body`, `Environment`, ...). A `RequestTemplate` holds unresolved `{{var}}` text; an `HttpRequest` is the resolved, ready-to-send form.
  - `port/in/`: use-case interfaces (`ParseHttpFile`, `RunRequest`) that front ends call.
  - `port/out/`: interfaces the core needs from the outside (`HttpExecutor`, `FileReader` for `< path` bodies, `EnvironmentRepository`).
  - `application/`: implementations (`HttpFileParser`, `VariableResolver`, `RequestRunner`). `RequestRunner` resolves variables in URL, headers and body (later variable maps override earlier ones), reads file bodies through `FileReader`, then delegates to `HttpExecutor`.
- `adapters/` — driven adapters implementing the outbound ports: `JdkHttpExecutor` (JDK `java.net.http`), `LocalFileReader`, `JsonEnvironmentRepository` (reads `http-client.env.json` plus `.private.env.json`, via kotlinx-serialization). Depends on `core` via `api`.

New I/O capabilities belong as a port in `core/port/out` with its implementation in `adapters`, not as a dependency of `core`.
