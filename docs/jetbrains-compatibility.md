# Compatibility with the JetBrains HTTP Client

This project **implements a practical subset of the JetBrains `.http` format, with additional testing and execution features.**
It is not a drop-in replacement: many everyday files (requests, headers, JSON bodies, `{{variables}}`, environments, `###`
separators, `# @name`, chaining on `{{name.response...}}`) run unchanged, but some syntax is not supported, and a few
behaviours differ on purpose. Where something is unsupported we fail loudly (a parse error or a warning) rather than send a
wrong request.

Positioning (from [competitive-gaps.md](competitive-gaps.md)): an IDE-native, Git-native client for the `.http` format, with one
execution engine behind a CLI, a desktop app and an IntelliJ plugin. Things only we do (assertions as plain comments, JUnit
and JSON reports, `--fail-on-http-error`, a variable analysis for editors) are marked "Extra".

Status values:

- **Supported**: same syntax and, as far as we know, the same meaning.
- **Partial**: accepted, but with limits or differences described in the note.
- **Not supported**: not implemented. The note says what happens (error, warning or ignored).
- **Different**: implemented, but behaving differently by design (see "Deliberate deviations").
- **Extra**: exists here only.

"unverified" in the JetBrains column means the JetBrains documentation pages we could fetch did not cover it (see
"Sources and limits"); we do not guess. Details of our side are in [features.md](features.md).

## Request line and methods

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| Standard methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`, `CONNECT`) | yes | Supported | Case-insensitive here. See [features.md](features.md#http-files). |
| Method omitted (bare URL) | means `GET` | Supported | |
| Custom methods | added through a quick fix / settings | Different | Any unknown method word is a parse error naming the line, with a "did you mean" hint for a one-letter typo. There is no way to register a custom method. |
| `HTTP/1.1` / `HTTP/2` / `HTTP/2 (Prior Knowledge)` after the URL | selects the version; without it, HTTPS tries HTTP/2 and falls back | Partial | A trailing `HTTP/x` is parsed and ignored, never sent as part of the URL. The JDK client negotiates the version itself; it cannot be pinned per request. |
| Multi-line URL (indented `?a=1` / `&b=2` lines) | yes | Supported | Indented query lines extend the URL. |
| `###` separator, optional text after it | yes | Supported | Text after `###` is not used as a request name here (use `# @name`). |
| `GRAPHQL`, `WEBSOCKET`, `GRPC` request types | yes (GraphQL and gRPC need plugins) | Not supported | Parse error naming the line; the file does not run and nothing is sent as plain HTTP. See "Protocols". |

## Headers, bodies and multipart

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `Name: value` headers, indented continuation lines | yes | Supported | A header line without `:` is a parse error. |
| Inline body after a blank line | yes | Supported | Sent as written after variable substitution. |
| `< ./file` body | yes | Different | Same syntax, relative to the `.http` file. Reads are confined to that file's folder plus an allow-list. |
| `<@ ./file` body (substitute variables inside the file) | unverified (the syntax page covers `<` only) | Partial | The file is read and sent, but variables inside it are NOT substituted (a warning is reported). |
| `application/x-www-form-urlencoded` | `key=value` pairs, optionally one per line | Partial | Sent as written, as plain body text. We do not join or encode lines for you. |
| `multipart/form-data` written by hand (boundary lines in the body) | yes, with `filename` parts for uploads | Partial | Boundary text typed in the body is sent as text. File parts (`< ./file` inside a part) and uploads are not supported. Planned as T8 in [PLAN.md](PLAN.md). |
| `# @no-auto-encoding` | sends parameters and body unencoded | Not supported | The setting is ignored (unknown settings are ignored). We do not re-encode the URL or body on our own either. |
| Silently managed headers | n/a | Different | The JDK client drops user-set `Host`, `Content-Length`, `Connection`, `Expect`, `Upgrade` ([known-limitations.md](known-limitations.md)). |

## Variables and environments

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `{{var}}` in URL, headers, body | yes | Supported | An undefined variable is an error; nothing is sent with a placeholder. |
| In-file variables `@name = value` | yes, scoped to the file | Supported | Apply from their line to the end of the file; later redefinition replaces. Values may use `{{...}}`. |
| `http-client.env.json` | yes, environment chosen in the IDE | Supported | Same shape (`{"dev": {...}}`). Searched upward from the `.http` folder (stops at the first `.git` folder, never enters your home or temp folder). |
| `http-client.private.env.json` | yes, overrides the public file | Supported | Merged on top of the shared file. |
| `$shared` environment, user-specific env files | unverified | Not supported | Not read. |
| "(none)" environment | n/a | Different | Really means none: no fallback to another environment; a vanished name is an error. |
| Environment values referencing other variables | unverified | Different | A value containing `{{` is sent as is. Compose with in-file variables instead. |
| Variable overrides from the command line | `-V`, `-P` in `ijhttp` | Extra | `httpc --var NAME=VALUE` (repeatable; beats file and environment variables). |
| `{{name.response.body.$.x}}`, `.headers.X`, `.status` chaining | yes | Partial | Ours supports a JSONPath subset (`$`, `.key`, `['key']`, `[index]`; no filters), plus XPath and regex as extras. The JetBrains pages we fetched did not describe this syntax, so how far their JSONPath goes is unverified. |
| `{{$uuid}}`, `{{$random.uuid}}` | yes | Supported | |
| `{{$timestamp}}`, `{{$isoTimestamp}}` | yes | Supported | Offset syntax (`{{$timestamp 1 d}}`) is our own design; it may not match JetBrains'. |
| `{{$randomInt}}` | integer 0 to 1000 per docs | Partial | Ours is 0 to 999; `{{$randomInt 5 10}}` for a range is our extension. |
| `{{$random.integer(a,b)}}`, `.float`, `.alphabetic(n)`, `.alphanumeric(n)`, `.hexadecimal(n)`, `.email` | yes | Supported | Argument rules in [features.md](features.md#dynamic-variables). |
| Other `$random.*` (Faker-based: `name`, `address`, `company`, ...) | yes | Not supported | Fails as an undefined variable. |
| `{{$env.NAME}}` | OS environment variable | Different | Opt-in: a name must be allowed with `--allow-env`, `-Dhttpclient.allowEnv` or `HTTPC_ALLOW_ENV`. |
| `{{$processEnv NAME}}` | unverified (not on the pages we could fetch) | Different | Accepted as an alias of `$env.NAME`, with the same allow-list. |
| `{{$datetime ...}}`, `{{$localDatetime ...}}` | unverified | Not supported | Fails as an undefined variable. |
| `{{$projectRoot}}`, `{{$historyFolder}}` | path helpers | Not supported | Fails as an undefined variable. |
| Pre-request scripts `< {% ... %}`, `request.variables` | yes | Not supported | See "Response handling". |
| Variable diagnostics, completion data, find usages | in the IDE | Extra | `VariableAnalysis` in `core` ([features.md](features.md#editor-diagnostics-analysis)); not yet wired into the editors. |

## Request separators, names and comments

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `# @name x` / `// @name x` | yes (`# @name = x` also) | Partial | `# @name x` and `// @name x`. Whether the `=` form is accepted is not claimed here. Names may contain dots. |
| `#` and `//` comments | yes | Supported | |
| Name from text after `###` | yes | Not supported | Use `# @name`. |
| `import` / `run` directives (run other `.http` files or requests, with `(@var=value)` overrides) | yes | Not supported | Not implemented. Unknown lines are not interpreted. Chaining within one file works. |

## Request settings and directives

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `# @no-redirect` | yes | Supported | Optional `true`/`false` value is our extension. |
| `# @no-cookie-jar` | yes | Supported | Neither sends nor stores cookies for that request. |
| `# @timeout` | "new packet" timeout, default 60 s | Different | Ours is a total deadline for the whole exchange (headers and body, across redirects). Default is also 60 s (CLI `--timeout`, `-Dhttpclient.timeout`, `HTTPC_TIMEOUT`). Units: plain number = seconds, `ms`, `s`, `m`; no decimals. |
| `# @connection-timeout` | yes, default 60 s | Not supported | Ignored. The client has a fixed 30 s connect timeout. |
| `# @no-log` | skip request history | Not supported | Ignored. |
| `# @proxy host:port` | unverified (not on the pages we could fetch) | Extra | Plain HTTP proxies only; no `https://`/`socks://`, no credentials. |
| `# @use-os-credentials` | unverified | Not supported | Ignored. |
| Unknown `# @setting` | n/a | Different | Unknown settings are ignored silently. Invalid values of known ones are errors before anything is sent. |
| `# @assert ...` | n/a | Extra | Declarative assertions; see "Assertions". |

## Response handling

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `> {% ... %}` JavaScript handler | yes | Not supported | Skipped with a warning (`HttpFile.warnings`; the CLI prints it on stderr); the request still runs. Script engine deferred in [competitive-gaps.md](competitive-gaps.md). |
| `> ./handler.js` external script | yes | Not supported | Skipped with a warning. |
| `client.global.set(...)` and other script APIs | yes | Not supported | Use `{{name.response...}}` chaining or in-file variables. |
| `>> file` (save, never overwrite, numeric suffix) | yes | Supported | Confined to allowed folders; atomic, owner-only, byte exact. See [features.md](features.md#saved-responses). |
| `>>! file` (overwrite) | yes | Supported | |
| `<> file` (link a stored response) | yes; the IDE rewrites the file | Different | Parsed and kept, but we never edit your `.http` file; the link is recorded in `.http-history/`. Opening a linked response is not implemented yet (T18, T27). |
| Response preview, compare, history of 50 requests | in the IDE | Partial | Plugin and desktop show the response; history browser and compare are planned (T18). Stored runs exist for `<>` links only. |
| SSE (`text/event-stream`) / NDJSON streaming view | yes, in the IDE | Not supported | The body is read until the stream ends or the timeout expires; no incremental view. |

## Assertions and tests

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `client.test` / `client.assert` in handler scripts | yes | Not supported | The scripts are skipped. |
| `# @assert status == 200`, `headers.X`, `body.$.path` ... | n/a | Extra | Plain comment lines before a request; operators `==`, `!=`, `<`, `contains`, `matches`, `exists` and more. Not portable: JetBrains ignores them (they are comments). |
| JUnit XML report | `ijhttp --report` | Extra | `httpc --report-junit FILE`; also `--report-json`. Failed tests there come from scripts; here from `# @assert` lines. Files are not interchangeable. |
| HTTP error status fails the run | unverified | Extra | `httpc --fail-on-http-error` (status >= 400, exit 1). By default only transport errors and failed assertions fail. |

## Authentication

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `Authorization: Basic user password` (auto Base64) | yes | Supported | Also `Basic user:pass`. A single token without `:` is taken as already encoded. See [features.md](features.md#authorization-shortcuts). |
| `Authorization: Bearer {{token}}` | plain header | Supported | Just a header; no helper needed. |
| Digest | `Authorization: Digest user password` | Not supported | Sent as a literal header value; no challenge/response. |
| OAuth 2.0 (`{{$auth.token("id")}}`, `{{$auth.idToken(...)}}`, auth config in the env file) | yes (the docs we fetched only link to it; the `ijhttp` page says the CLI does not support OAuth 2.0) | Not supported | Fails as an undefined variable. Full OAuth2 flows are out of scope in [PLAN.md](PLAN.md). Get a token with a request and chain on it instead. |
| OS credentials, other schemes | unverified | Not supported | |

## Protocols

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| HTTP/1.1 | yes | Supported | Through `java.net.http`. |
| HTTP/2 | selectable per request | Partial | See "Request line"; the JDK client may use it, but you cannot choose it. |
| GraphQL (`GRAPHQL` keyword) | yes, plugin | Not supported | Parse error. A GraphQL call over plain `POST` with a JSON body works as an ordinary request. |
| WebSocket (`WEBSOCKET`, `===` messages) | yes | Not supported | Parse error. Out of scope in [PLAN.md](PLAN.md). |
| gRPC (`GRPC`, unary and server-streaming) | yes, plugins | Not supported | Parse error. Out of scope. |
| Server-sent events | yes (IDE view) | Not supported | See "Response handling". |

## Cookies and redirects

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| Automatic cookie jar | persisted in `http-client.cookies` (up to 300) | Different | In-memory per session (CLI run; per file and environment in the apps); nothing persisted. No `SameSite`, no public-suffix list. See [features.md](features.md#cookie-jar). |
| Custom `Cookie` header | yes | Supported | Sent as is; the jar adds nothing to it. |
| Redirect following | yes | Supported | Our own follower: up to 10 hops; 301/302 on POST and 303 become GET; `Set-Cookie` on a 302 is stored. |
| Credentials on redirects | unverified | Extra | `Cookie`, `Authorization`, `Proxy-Authorization` are dropped when a redirect leaves the origin. Other custom headers (for example `X-API-Key`) are still forwarded. |

## Proxy

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| IDE proxy settings (manual proxy, authentication) | yes | Not supported | The IDE plugin does not read the IDE proxy settings; the JDK default selector is used unless a request sets `# @proxy`. |
| Per-request proxy | unverified | Extra | `# @proxy host:port`, HTTP proxies only, no credentials. |

## Certificates and SSL

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| `SSLConfiguration` in `http-client.private.env.json` (`clientCertificate`, `clientCertificateKey`, `hasCertificatePassphrase`) | yes (not in the CLI) | Not supported | Ignored; treated as ordinary variables. No client certificates. |
| `verifyHostCertificate: false` | yes (the only SSL setting in the CLI) | Not supported | Certificates are always verified by the JDK. |

## Importing

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| cURL to request | paste or convert in the IDE | Not supported | Revisit after the quick wins ([competitive-gaps.md](competitive-gaps.md)). |
| Postman collection import | yes | Not supported | Same. |
| OpenAPI to requests | yes, in the IDE | Not supported | |
| Request to cURL | yes | Not supported | |

## CLI and CI

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| Separate CLI | `ijhttp` (Docker, ZIP, Homebrew; JDK 25+ per its page) | Supported | `httpc` built from `cli/` (needs JDK 21). |
| Select environment | `--env`, `--env-file`, `--private-env-file` | Partial | `--env NAME`; the env file is found by searching upward, with no option to point to a file. |
| Report | `--report` (JUnit XML) | Extra | `--report-junit`, `--report-json`; exit codes `0/1/2/3`. |
| Run one request | unverified | Extra | `--request NAME|INDEX` plus the requests it chains on. |
| Output | log levels `-L` | Different | `--quiet`, `--output FILE` (byte-exact body of one request), summary line on stderr. |
| Scripts and WebSocket in the CLI | yes | Not supported | Same gaps as above. |

## Editor support

| Feature | JetBrains HTTP Client | This project | Notes |
|---|---|---|---|
| Syntax highlighting, run gutter, tool window | IntelliJ Ultimate (bundled) | Supported | Our plugin works in Community IDEs; do not install next to the bundled plugin (both claim `.http`). The plugin is built but not yet verified by hand in a real IDE (T21). |
| Completion, navigation, inspections | yes | Not supported | Planned (T16, T22). |
| Desktop app | n/a | Extra | Compose Desktop: editor, environment picker, response viewer. |

## Deliberate deviations

Decided in review, not bugs (also listed in [known-limitations.md](known-limitations.md)):

- **File access is confined.** `< path`, `<@ path` and `>> path` stay inside the `.http` file's folder unless you allow more with `--allow-file-root` / `HTTPC_ALLOW_FILE_ROOTS`.
- **`$env` is opt-in.** A downloaded file cannot read your environment variables until you allow their names.
- **An unknown method is a parse error**, not a URL. `GRAPHQL`, `WEBSOCKET` and `GRPC` are parse errors too, so nothing is sent as plain HTTP by accident.
- **Response-handler scripts are skipped** with a warning; we do not run JavaScript.
- **`<>` never edits your file.** Links are kept in `.http-history/`.
- **`# @timeout` is a total deadline**, with a 60 s default, and a redirect follower of our own never forwards credentials across origins.
- **"(none)" means no environment.** There is no silent fallback.

## Not planned / later

From [PLAN.md](PLAN.md) ("Later / out of scope for now") and [competitive-gaps.md](competitive-gaps.md):

- Planned: multipart and form bodies with file parts (T8); completion and inspections in the editors (T16, T22); history browser and opening `<>` links (T18, T27).
- Later or out of scope: GraphQL, WebSocket and gRPC; full OAuth2 flows and Digest; response-handler scripts (would need a sandboxed script engine in `adapters`); Postman and cURL import; OpenAPI-based completion; team sync (Git is the sync mechanism).
- Not on any list: `import`/`run` directives, client certificates and `verifyHostCertificate`, `# @connection-timeout`, `# @no-log`, Faker-based `$random.*`.

## Sources and limits

Checked in November 2026 (2026-11).

- **Our side** was checked against [features.md](features.md), [known-limitations.md](known-limitations.md), [PLAN.md](PLAN.md), the changelog and the code: `HttpFileParser`, `HttpLineSyntax`, `RequestOptionsParser`, `DynamicVariables`, `ResponseExtractor`, `BasicAuth`, `RedirectFollower` and `JdkHttpExecutor`.
- **The JetBrains side** was summarised from the official pages: [HTTP Client in the editor](https://www.jetbrains.com/help/idea/http-client-in-product-code-editor.html), [HTTP request syntax](https://www.jetbrains.com/help/idea/exploring-http-syntax.html), [variables](https://www.jetbrains.com/help/idea/http-client-variables.html), [response handling](https://www.jetbrains.com/help/idea/http-response-handling-examples.html), [HTTP Client reference](https://www.jetbrains.com/help/idea/http-client-reference.html) and [HTTP Client CLI](https://www.jetbrains.com/help/idea/http-client-cli.html).
- The page fetch returns a machine summary, so details can be missed. The syntax page did not cover `<@`, `# @proxy`, `# @use-os-credentials`, `$processEnv`, `$datetime`, auth variables or exit codes; the variables page did not cover `$shared`, auth variables or response references; the reference page covered only the script `client` object. The dedicated OAuth 2.0 and authentication pages were not fetched. Rows depending on those are marked "unverified" or phrased from what the fetched pages said.
- JetBrains changes the product often. Re-check a row before relying on it when moving files between tools.
