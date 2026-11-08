# Differences from the JetBrains HTTP Client

This project **implements a practical subset of the JetBrains `.http` format, with additional testing and execution features.**
It is not a drop-in replacement. This page lists only where we differ; everything else is described in [features.md](features.md).
Positioning (from [competitive-gaps.md](competitive-gaps.md)): an IDE-native, Git-native client for the `.http` format, with one
execution engine behind a CLI, a desktop app and an IntelliJ plugin. Features that exist only here (assertions, reports,
`--fail-on-http-error`, ...) are documented in [features.md](features.md) and the [README](../README.md), not here.

## What works the same

Requests with the usual methods, headers, JSON and text bodies, `{{variables}}`, environments from `http-client.env.json`
(plus the private file), `###` separators, `# @name`, chaining on `{{name.response...}}`, the cookie jar and redirects run
the same in everyday files. Our exact rules are in [features.md](features.md).

What happens to something we do not support varies. It is one of: a **parse error** (the file does not run, nothing is sent),
a **warning** (the request still runs), an **undefined-variable error** (nothing is sent), or **silently ignored or sent as
written**. Each row below says which. "unverified" in the JetBrains column means the JetBrains pages we could fetch did not
cover it (see "Sources and limits"). Status: **Partial** (accepted with limits), **Not supported**, or **Different**
(implemented, behaves differently by design; see "Deliberate deviations").

## Request line, headers and bodies

| Feature | JetBrains HTTP Client | Status | What happens here |
|---|---|---|---|
| Custom methods | via quick fix / settings | Different | Unknown method word: parse error, no way to register one ([features.md](features.md#http-files)). |
| `HTTP/2`, `HTTP/1.1` after the URL | selects the version | Partial | `HTTP/1.1`, `HTTP/2` are stripped and ignored (the JDK negotiates); `HTTP/2 (Prior Knowledge)` is not stripped and ends up in the URL. |
| `GRAPHQL`, `WEBSOCKET`, `GRPC` requests | yes (plugins for some) | Not supported | Parse error naming the line; nothing is sent. |
| `import` / `run` directives | yes | Not supported | Parse error: the line is read as an unknown method. Chaining inside one file works. |
| Name from text after `###` | yes | Not supported | Ignored; use `# @name x`. `# @name = x` names the request `=`, and `# @name=x` leaves it unnamed ([features.md](features.md#request-chaining)). |
| `< ./file` body | yes | Different | Relative to the `.http` file and confined to its folder plus an allow-list ([features.md](features.md#http-files)). |
| `<@ ./file` body | unverified | Partial | File is sent as is; variables inside are not substituted (warning). |
| `application/x-www-form-urlencoded` | pairs, optionally one per line | Partial | Sent as written; we do not join or encode lines. |
| `multipart/form-data` | boundaries and file parts | Partial | Hand-typed boundary text is sent as text; file parts are not supported (T8 in [PLAN.md](PLAN.md)). |
| `# @no-auto-encoding` | sends unencoded | Not supported | Silently ignored. |
| `Host`, `Content-Length`, `Connection`, `Expect`, `Upgrade` headers | sent | Different | The JDK client drops them silently ([known-limitations.md](known-limitations.md)). |

## Variables and environments

| Feature | JetBrains HTTP Client | Status | What happens here |
|---|---|---|---|
| `$shared` in the env file | shared variables | Not supported | Not read as shared variables; it appears as a selectable environment named `$shared`. |
| `SSLConfiguration` in env files | client certificates | Not supported | Not read as ordinary variables: only JSON primitives are kept, nested objects are dropped. No client certificates. |
| "(none)" environment | n/a | Different | Means none: no fallback; a vanished name is an error ([features.md](features.md#environments)). |
| Environment picker (IDE plugin) | selector in the `.http` editor | Same place | The picker sits in the header of each `.http` editor; the choice is per project until the IDE restarts and applies to runs from every file. |
| Env value containing `{{...}}` | unverified | Different | Sent as is, not expanded. |
| `{{name.response.body.$.x}}` | yes | Partial | JSONPath subset, no filters; JetBrains' extent is unverified ([features.md](features.md#request-chaining)). |
| `{{$timestamp}}`, `{{$randomInt}}` | yes | Partial | Offset syntax and `$randomInt a b` are ours; `$randomInt` is 0 to 999 here (their docs say 0 to 1000) ([features.md](features.md#dynamic-variables)). |
| `{{$env.NAME}}`, `{{$processEnv NAME}}` | OS variable / unverified | Different | Opt-in: the name must be allowed (`--allow-env`, `HTTPC_ALLOW_ENV`). |
| Faker-based `{{$random.*}}` (`name`, `address`, ...) | yes | Not supported | Undefined-variable error. |
| `{{$datetime ...}}`, `{{$localDatetime ...}}` | unverified | Not supported | With an argument (`{{$datetime iso8601}}`) the text is sent literally, no error; without one it is an undefined-variable error. |
| `{{$projectRoot}}`, `{{$historyFolder}}` | path helpers | Not supported | Undefined-variable error. |
| Pre-request scripts `< {% %}` | yes | Not supported | Skipped with a warning; the request runs. |

## Settings, response handling and tests

| Feature | JetBrains HTTP Client | Status | What happens here |
|---|---|---|---|
| `# @timeout` | "new packet" timeout | Different | Total deadline for the exchange, default 60 s ([features.md](features.md#request-settings)). |
| `# @connection-timeout` | yes | Not supported | Silently ignored; fixed 30 s connect timeout. |
| `# @no-log`, `# @use-os-credentials` | yes / unverified | Not supported | Silently ignored. |
| Unknown `# @setting` | n/a | Different | Silently ignored; invalid values of known settings are errors. |
| `> {% ... %}`, `> ./handler.js` | JavaScript handlers | Not supported | Skipped with a warning; the request runs. Deferred in [competitive-gaps.md](competitive-gaps.md). |
| `client.global.set`, `client.test`, `client.assert` | yes | Not supported | Scripts are skipped; use chaining, in-file variables or `# @assert` ([features.md](features.md#assertions)). |
| `<> file` | IDE rewrites your file | Different | Parsed and kept; we never edit the file, links go to `.http-history/` ([features.md](features.md#saved-responses)). |
| Response history, compare | in the IDE | Partial | Viewer only; history browser and compare planned (T18). |
| SSE / NDJSON streaming view | yes | Not supported | Body is read until the stream ends or the timeout; no incremental view. |

## Authentication, protocols, cookies, proxy, certificates, import

| Feature | JetBrains HTTP Client | Status | What happens here |
|---|---|---|---|
| Digest | `Authorization: Digest user password` | Not supported | Sent as a literal header value; no challenge/response ([features.md](features.md#authorization-shortcuts)). |
| OAuth 2.0 (`{{$auth.token("id")}}`) | yes (not in `ijhttp`) | Not supported | Undefined-variable error. Chain on a token request instead. |
| GraphQL, WebSocket, gRPC, SSE, HTTP/2 settings | yes | Not supported | See above; a GraphQL call as plain `POST` with a JSON body works. |
| Cookie jar | persisted in `http-client.cookies` | Different | In memory per session, nothing persisted, no public-suffix list ([features.md](features.md#cookie-jar)). |
| IDE proxy settings | yes | Not supported | Not read; the JDK default selector is used. |
| `verifyHostCertificate: false`, client certificates | yes | Not supported | Silently ignored (see `SSLConfiguration` above); certificates are always verified. |
| cURL, Postman, OpenAPI import; request to cURL | yes | Not supported | Not implemented ([competitive-gaps.md](competitive-gaps.md)). |
| `ijhttp` `--env-file`, `--private-env-file` | yes | Not supported | `httpc --env NAME` only; the env file is found by searching upward ([features.md](features.md#command-line)). |

## Editor support

Checked by hand on IntelliJ IDEA Ultimate 2026.3 (highlighting, run gutter, tool window, environments, chaining; see
`intellij-plugin/README.md`); a real Community install and the 2026.1 baseline are not verified. Assertion results are
shown in the tool window but not checked by hand, the run shortcut `Ctrl+Alt+R` is not yet tried in a running IDE, and completion, navigation and inspections are not
available (T16, T22). Do not install the plugin
next to the bundled JetBrains one (both claim `.http`). See [features.md](features.md#intellij-plugin).

## Deliberate deviations

Decided in review, not bugs (also in [known-limitations.md](known-limitations.md)):

- **File access is confined.** `< path`, `<@ path` and `>> path` stay inside the `.http` file's folder unless you allow more.
- **`$env` is opt-in.** A downloaded file cannot read your environment variables until you allow their names.
- **Unsupported request types fail.** An unknown method, `GRAPHQL`, `WEBSOCKET` and `GRPC` are parse errors, so nothing is sent as plain HTTP by accident.
- **Handler scripts are skipped** with a warning; we do not run JavaScript.
- **`<>` never edits your file.**
- **Redirects use our own follower.** `Cookie`, `Authorization` and `Proxy-Authorization` are dropped when a redirect leaves the origin, but other headers (for example `X-API-Key`) still cross origins and 307/308 re-send the body ([features.md](features.md#cookie-jar)).
- **"(none)" means no environment.**

## Not planned / later

From [PLAN.md](PLAN.md) and [competitive-gaps.md](competitive-gaps.md):

- Planned: form and multipart file parts (T8); editor completion and inspections (T16, T22); history browser and `<>` links (T18, T27).
- Later or out of scope: GraphQL, WebSocket, gRPC; full OAuth 2 and Digest; handler scripts; Postman and cURL import; team sync.
- Not on any list: `import`/`run`, client certificates, `# @connection-timeout`, `# @no-log`, Faker-based `$random.*`.

## Sources and limits

Checked in November 2026.

- **Our side** was checked against [features.md](features.md), [known-limitations.md](known-limitations.md), [PLAN.md](PLAN.md) and the code (`HttpFileParser`, `HttpLineSyntax`, `VariableResolver`, `JsonEnvironmentRepository`, `RedirectFollower`).
- **The JetBrains side** was summarised from the official pages: [editor](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html), [syntax](https://www.jetbrains.com/help/idea/exploring-http-syntax.html), [variables](https://www.jetbrains.com/help/idea/http-client-variables.html), [response handling](https://www.jetbrains.com/help/idea/http-response-handling-examples.html), [reference](https://www.jetbrains.com/help/idea/http-client-reference.html) and [CLI](https://www.jetbrains.com/help/idea/http-client-cli.html).
- Page fetches return a machine summary, so details can be missed. Not covered: `<@`, `# @use-os-credentials`, `$processEnv`, `$datetime`, `$shared`, auth variables, response references and the dedicated OAuth 2.0 pages. Those rows are marked "unverified" or phrased from what the fetched pages said. The editor behaviour of our plugin was checked by hand only on IntelliJ IDEA Ultimate 2026.3; a real Community install and the 2026.1 baseline are not verified.
- JetBrains changes the product often; re-check a row before moving files between tools.