# http-client

A Postman-style HTTP client that implements a practical subset of the JetBrains `.http` format, with additional testing and execution features. See [how it differs from the JetBrains HTTP Client](docs/jetbrains-compatibility.md).

## Ways to use it

- **Command line** (`httpc`): run, check and report on `.http` files, in CI too. `./gradlew :cli:run --args="requests.http --env dev"`. All options: [command line reference](docs/cli-reference.md).
- **Desktop app** (Windows, macOS, Linux): [README](desktop/README.md).
- **IntelliJ plugin** (works in Community editions): [README](intellij-plugin/README.md).
- **Coding agents**: the MCP server `httpc-mcp` and a Claude Code skill: [guide](docs/guide/agents.md), [skill](agent-skill/README.md).

Downloads and install steps: [getting started](docs/getting-started.md).

## Build and test

Kotlin (JVM 21), Gradle. `./gradlew test` runs the tests. Coverage and mutation reports: [quality reports](docs/quality.md). Modules and layout: [architecture](docs/architecture.md); working rules: [AGENTS.md](AGENTS.md).

## Documentation

[getting started](docs/getting-started.md) · [features](docs/features.md) · [architecture](docs/architecture.md) · [what is stable](docs/stability.md) · [design notes](docs/design-notes.md) · [differences from JetBrains](docs/jetbrains-compatibility.md) · [user guide](docs/user-guide-plan.md) · [plan](docs/PLAN.md) · [changelog](CHANGELOG.md)
