# MCP server: safety model (T34)

Written before the code, as [PLAN.md](PLAN.md) asks. The `mcp-server` module (still to be built) lets a coding agent list, run and analyse requests. This page fixes what it may touch and what it may never return. A feature that does not fit here is not added until this page changes.

## Who is trusted

| Party | Trust |
|---|---|
| The user who starts the server and its flags | Trusted. Every permission below is granted by a start flag, never by a tool call. |
| The agent calling the tools | Not trusted. It can be steered by text it reads (prompt injection), so it gets only what a flag allows. |
| `.http` files | Not trusted: a downloaded file can aim a request anywhere or read a file. The existing rules apply (`FileAccessPolicy`, opt-in `$env`). |
| Response bodies and headers | Not trusted. They go back to the agent as data, and may carry instructions aimed at it. |

## Rules

1. **File roots.** The server needs at least one `--root DIR` (repeatable) and refuses to start without one. A tool takes a path relative to a root; it is normalised, symlinks are resolved and the real path must lie inside a root (the same check as `FileAccessPolicy`). `< path` bodies are confined to the roots too. No tool lists or reads anything else.
2. **Host allow-list.** Only hosts named by `--allow-host` (exact name or `*.suffix`) are reachable. The default is loopback only (`localhost`, `127.0.0.1`, `::1`). The check is a decorator around `HttpExecutor` in `adapters`, so it sees the resolved URL of every request and of every redirect hop. Link-local and cloud-metadata addresses (`169.254.0.0/16`, `fd00::/8`) need to be named explicitly. A rejected request fails with a message naming the host and the flag, and nothing is sent. Residual risk: a name that resolves to another address between the check and the connection (DNS rebinding); `--allow-host` with a literal IP avoids it.
3. **Read only by default.** `GET`, `HEAD` and `OPTIONS` run. Any other method is refused unless the user starts the server with `--allow-write`, and then, when the client supports MCP elicitation, each such request still asks the user first (method, host and path shown, no body and no values). `run_all` and chained runs check every request they would send before the first one goes out, so a refusal never leaves a half-run chain.
4. **Private values never leave.** `list_environments` returns names only. Values from `http-client.private.env.json`, `$env.X` and `$processEnv X` are never returned. Tools return requests as written (with `{{var}}`), not resolved. In a response, any substituted value (private or not) is replaced by `{{name}}` before it is returned, as `FailureScrubber` already does for error messages. This is best effort: a value the server changed (encoded, split, hashed) is not recognised. Error messages and logs follow the same rule.
5. **No writes by the server.** `>> path`, `>>!` and `<> path` fail (no `ResponseSaver`, no history link recorder is wired in). The server does not edit environments, convert collections or write reports. Its only side effect is the HTTP requests of rule 3.
6. **No ambient authority.** `$env` is empty unless the user passes `--allow-env NAME`; the agent cannot extend it. Stdio transport only, no listening port.
7. **Bounded output and work.** A body is cut at 64 KB (with a note saying so), `run_all` stops at 100 requests, every request gets the default timeout (60 s) and at most 5 minutes in total. The limits are start flags with these defaults.
8. **Responses are labelled.** A result wraps the body as data from a remote server, so a client can show it as untrusted. The server never follows an instruction found in a response.

## Tools

| Tool | Does | Safety notes |
|---|---|---|
| `list_requests(file)` | Names, methods and URLs as written | Reads inside the roots only |
| `list_environments(file)` | Environment names | Rule 4: no values |
| `analyze(file, environment?)` | `VariableAnalysis` diagnostics | Like `httpc --check`: sends nothing |
| `run(file, request, environment?)` | Runs one request (and the ones it chains on) | Rules 2, 3, 4, 7 |
| `run_all(file, environment?)` | Runs the file in order | Pre-checks every request first (rule 3) |

## Build order

1. `HostAllowListExecutor` and the method check in `adapters`, with tests (the redirect hop test is the important one).
2. Response redaction from the substituted-value map `RequestSession` already keeps.
3. The `mcp-server` module: stdio loop, the five tools, the start flags, tests that start it against `mock-server` and try to break each rule above (a path outside the roots, a redirect to a host not allowed, a private value echoed back by `/headers`, a `POST` without `--allow-write`).
4. The user-guide page and the agent skill ([agent-skill](../agent-skill)) learn the tools.
