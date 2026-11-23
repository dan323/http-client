# What is stable

**For 1.0.0.** From 1.0.0 on, the things listed under "Stable" do not change in a way that breaks a script, a CI job or an agent that uses them, except in a new major version. A new option, a new field, a new tool or a new `.http` feature may be added in a minor version. Before 1.0.0 (every `0.x` release) any of it may change; the [changelog](../CHANGELOG.md) says when it did.

## Stable

### Command line (`httpc`)

- The options in the [command line reference](cli-reference.md): `--env`, `--request`, `--allow-env`, `--allow-file-root`, `--list`, `--check`, `--report-json`, `--report-junit`, `--quiet`, `--fail-on-http-error`, `--output`, `--var`, `--timeout`, and the `convert-bruno` command. Their names and what they mean. An option may be added; none is removed or changes meaning.
- The exit codes: `0` success, `1` a request, assertion or setup failed (or `--check` found an error), `2` bad arguments, `3` the run passed but a report or output file could not be written.
- The `--check` line format `FILE:LINE:COLUMN: error|warning: message [code]`. The `code` values are stable (a new one may appear); the `message` text is not.

### Run reports

- JSON: `version` (1), `passed`, `totals` (`requests`, `passedRequests`, `failedRequests`, `assertionsPassed`, `assertionsFailed`, `assertionsNotRun`, `durationMillis`) and `results` (per request: `name`, `method`, `url`, `status`, `durationMillis`, `passed`, `assertions`, `assertionsNotRun`, `error`, `prerequisite`, `httpError`). Fields may be added; none is removed or renamed without raising `version`.
- JUnit XML: one `testcase` per request with the naming in [features](features.md#run-reports), the failure types `AssertionFailure` and `HttpStatusFailure`, and errors for requests that could not complete.

### The `.http` dialect

- Everything in [features](features.md) that the JetBrains HTTP Client also supports keeps working as it does today ([differences](jetbrains-compatibility.md)).
- This client's own extensions keep their syntax: `# @assert` lines, `$shared` environment variables, the `QUERY` method, request chaining with `{{name.response...}}`, the settings listed in [features](features.md#request-settings).
- Unsupported things stay unsupported with a warning (for example `> {% ... %}` response handlers), not silently different.
- Environment files: `http-client.env.json` and `http-client.private.env.json`, including the `$shared` entry.

### MCP server (`httpc-mcp`)

- The start flags in the [guide](guide/agents.md#5-allow-more) and their defaults. The server stays read-only, local and confined to `--root` unless a flag says otherwise; no flag will be silently loosened.
- The tools `list_requests`, `list_environments`, `analyze`, `run` and `run_all`, with their argument names (`file`, `request`, `environment`) and the result of each as structured content, described by the tool's `outputSchema` (the same JSON is in the text part). Fields may be added; none is removed or changes type.
- In a result: everything from the remote server stays under `remote` with `"untrusted": true`; secrets stay replaced by their variable name; `isError` is true when a request failed or was refused.
- The safety rules in [mcp-safety-model.md](mcp-safety-model.md).

## Not stable

- The wording of messages (errors, refusals, warnings). Match on exit codes, `code` values and JSON fields, not on text.
- The formatting of the JSON text part of an MCP result (indentation, key order). Read the structured content.
- Anything in `core`, `adapters`, `view-model` and the other modules as a library: they are not published, and their classes are not an API.
- The desktop app and the IntelliJ plugin: buttons, layout, shortcuts and settings may change in any release. What they run and report follows the rules above.
- The run history folder (`.http-history`) layout and the desktop settings file. They are read back by the desktop app and the plugin, not by scripts.
- The exact `.http` files and environment files that `httpc convert-bruno` writes (its mapping from Bruno is still partial, see [PLAN](PLAN.md), T35). The command and its options are stable.
- Performance and the exact timing of requests.

## Deprecation

A stable thing is deprecated in a minor release (documented in the changelog and, where it can, with a warning) and removed no sooner than the next major release.
