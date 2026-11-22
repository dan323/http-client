# MCP server: safety model (T34)

Written before the code, as [PLAN.md](PLAN.md) asks. The `mcp-server` module lets a coding agent list, run and analyse requests. This page fixes what it may touch and what it may never return. A feature that does not fit here is not added until this page changes.

## Who is trusted

| Party | Trust |
|---|---|
| The user who starts the server and its flags | Trusted. Every permission below is granted by a start flag, never by a tool call. |
| The agent calling the tools | Not trusted. It can be steered by text it reads (prompt injection), so it gets only what a flag allows. |
| `.http` files | Not trusted: a downloaded file can aim a request anywhere or read a file. The existing rules apply (`FileAccessPolicy`, opt-in `$env`). |
| Response bodies and headers | Not trusted. They go back to the agent as data, and may carry instructions aimed at it. |

## Rules

1. **File roots.** The server needs at least one `--root DIR` (repeatable, absolute) and refuses to start without one. A tool takes a path relative to a root; it is normalised, symlinks are resolved (reusing `realPathOf` of `LocalFileReader`) and the real path must lie inside a root. This tool-path check is new code: `FileAccessPolicy` only holds extra roots, and `LocalFileReader` always allows the `.http` file's own folder. `< path` bodies stay confined by `LocalFileReader` (the file's folder plus the roots). Reading `http-client.private.env.json` or `http-client.env.json` as a body is refused outright, whatever the root, so an allowed server cannot echo a secret file back. No tool lists or reads anything else.
2. **Host allow-list.** `--allow-host` takes an exact name, `*.suffix` (any depth below the suffix, never the bare suffix) or an IP/CIDR. The default is loopback (`localhost`, `127.0.0.1`, `::1`). The check is a decorator around `HttpExecutor` in `adapters`, so it sees every request and every redirect hop. It compares the lower-cased host without a trailing dot, ignores userinfo (it looks at the real host of the parsed URI) and refuses numeric host forms other than a canonical dotted quad (octal, hex, decimal and short forms such as `127.1` are refused, not converted); an IPv4-mapped IPv6 literal is judged as its IPv4 address. A name is not enough to prove where a request goes (`*.nip.io` resolves to anything), so the decorator resolves the name itself, checks **every** resolved address against the same list, refuses addresses that are not public unless the user listed that address or range: loopback, link-local (`169.254.0.0/16`, `fe80::/10`), private (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), shared (`100.64/10`), unspecified, multicast, reserved (`0/8`, `240/4`), `192.0.0/24`, the documentation ranges (`192.0.2/24`, `198.51.100/24`, `203.0.113/24`, `2001:db8::/32`), benchmarking (`198.18/15`), IPv4-compatible `::/96`, and, for 6to4 (`2002::/16`) and NAT64 (`64:ff9b::/96`), the IPv4 address they carry judged by the same list, and a literal IP in the URL must be listed itself. The JDK client cannot be told which address to connect to (an IP URL would break TLS names and the `Host` header), so it resolves the name again when it connects: a name that changes its answer in between (DNS rebinding) is not caught. Listing an IP literal rules that out. A rejected request fails with a message naming the host and the flag, and nothing is sent.
   **`# @proxy` is ignored by this server**: a request that sets one is refused, because the proxy would receive the request and its credentials. A proxy can be set by the user with a start flag, and is then itself checked against the list.
3. **Read only by default.** `GET`, `HEAD` and `OPTIONS` run. Any other method is refused unless the user starts the server with `--allow-write`. The server does not use MCP elicitation yet: `--allow-write` means such requests run without asking, and the server says so at start on stderr. Before a run, the server lists every request that would be sent, which includes the dependencies `runChained` pulls in for `run` and the whole file for `run_all`, and checks all of them against rules 2 and 3 before the first goes out, so a refusal never leaves a half-run chain.
4. **Private values never leave.** `list_environments` returns names only. Tools return requests as written (with `{{var}}`), not resolved. Responses are redacted by the server, not by `FailureScrubber`: every substituted value of the run (private environment values, `$env`, `$processEnv`, values of chained responses) is replaced by `{{name}}` in the body, in the headers and in error messages, whatever its length (the short-value exclusion of `FailureScrubber` does not apply: a short secret is replaced too, and a short non-secret value only costs readability). The Basic `Authorization` header is also redacted in its base64 form. `RequestSession` keeps its substituted-value map private and clears it on every run, so it must expose the values of the last run for this (build order, step 2). It is best effort: a value the server changed otherwise (hashed, split, re-encoded) is not recognised. Logs follow the same rule.
5. **No writes by the server.** `>> path`, `>>!` and `<> path` report "not available here" and write nothing: `SessionFactory.create(persistence = false)` leaves the saver and the history recorder out, and the MCP server uses it. The server does not edit environments, convert collections or write reports. Its only side effect is the HTTP requests of rule 3.
6. **No ambient authority.** `$env` is empty unless the user passes `--allow-env NAME`; the agent cannot extend it. Stdio transport only, no listening port.
7. **Bounded output and work.** A body is cut at 64 KB (with a note saying so). The caps count every request sent, chain dependencies and redirect hops included: 100 requests per call and 5 minutes in total, enforced as one deadline for the call (the redirect deadline is per chain, so it is not enough). A `# @timeout` in the file is clamped to the per-request limit (default 60 s), so a file cannot ask for `60m`: `SessionFactory.create(maxTimeoutMillis = ...)` clamps it before the redirect follower builds its chain deadline (so the limit covers the whole chain), and `GuardedHttpExecutor` clamps each hop again as a backstop. The limits are start flags with these defaults.
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

Step 1 is done (`HostAllowList`, `GuardedHttpExecutor`, `SessionFactory.create(persistence = false)` in `adapters`).

1. In `adapters`: `HostAllowListExecutor` (resolve and check every address) and the method check, with tests (redirect hops, `*.nip.io`-style names, IPv4-mapped IPv6, userinfo), `@proxy` refusal, the `@timeout` clamp, and the `SessionFactory` option that leaves out the saver and the history recorder.
2. (Done) Response redaction: `RequestSession` exposes the substituted values of the last run (names only for the agent), and a redactor replaces them in body, headers and errors, with tests for each source (private environment, `$env`, chained responses, short values, Basic header).
3. (Done) The `mcp-server` module: stdio loop, the five tools, the start flags, tests that start it against `mock-server` and try to break each rule above (a path outside the roots, a redirect to a host not allowed, a private value echoed back by `/headers`, a `POST` without `--allow-write`).
4. The user-guide page and the agent skill ([agent-skill](../agent-skill)) learn the tools.
