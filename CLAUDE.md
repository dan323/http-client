# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A Postman-style HTTP client compatible with the JetBrains `.http` file format. Kotlin (JVM 21 toolchain), Gradle Kotlin DSL multi-module build. Modules: `core`, `adapters`, front ends `cli`, `desktop` (Compose Desktop) and `intellij-plugin`, plus `mock-server` (httpbin-style server for `samples/`). Each module has its own README where relevant; see `docs/features.md` for the `.http` features.

## Commands

- Run all tests: `./gradlew test`
- One module: `./gradlew :core:test`, `:adapters:test`, `:cli:test`, `:desktop:test`, `:intellij-plugin:test`
- Single test class/method: `./gradlew :core:test --tests "com.dan323.httpclient.core.HttpFileParserTest"` (append `.methodName` for one method)

Tests use `kotlin("test")` on JUnit Platform. There is no linter configured.

## Architecture

Hexagonal layout; dependencies point inward only.

- `core/` — the hexagon. Must stay free of third-party runtime dependencies.
  - `domain/Model.kt`: data types (`HttpFile`, `RequestTemplate`, `HttpRequest`, `HttpResponse`, `Body`, `Environment`, ...). A `RequestTemplate` holds unresolved `{{var}}` text; an `HttpRequest` is the resolved, ready-to-send form.
  - `port/in/`: use-case interfaces (`ParseHttpFile`, `RunRequest`) that front ends call.
  - `port/out/`: interfaces the core needs from the outside (`HttpExecutor`, `FileReader` for `< path` bodies, `EnvironmentRepository`).
  - `application/`: implementations (`HttpFileParser`, `VariableResolver`, `RequestRunner`). `RequestRunner` resolves variables in URL, headers and body (later variable maps override earlier ones), reads file bodies through `FileReader`, then delegates to `HttpExecutor`.
  - `application/` also holds `RequestSession` (runs requests in order and resolves `{{name.response...}}` chaining), `withDependencies`, and `HttpLineLexer` (shared editor tokenizer).
- `adapters/` — driven adapters implementing the outbound ports: `JdkHttpExecutor` (JDK `java.net.http`), `LocalFileReader`, `JsonEnvironmentRepository` (reads `http-client.env.json` plus `.private.env.json`, via kotlinx-serialization). Depends on `core` via `api`.

New I/O capabilities belong as a port in `core/port/out` with its implementation in `adapters`, not as a dependency of `core`.
