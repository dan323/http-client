---
name: http-client
description: >-
  Write, check and run `.http` request files with the `httpc` command line (the JetBrains-style HTTP format,
  with requests separated by `###`, `{{variables}}`, `# @assert`, request chaining and `http-client.env.json`
  environments). Use when the user asks to call or test an HTTP API from a `.http` file, to add requests or
  assertions to one, to find out why one fails, or to produce a JUnit/JSON report of API checks.
---

# `.http` files and `httpc`

`httpc` runs a `.http` file: `httpc <file.http> [options]`. It is a practical subset of the JetBrains HTTP Client format, so write the dialect below and nothing else. If `httpc` is not installed, tell the user (installers and zips are on the project's GitHub releases); do not substitute curl silently.

## Loop to follow

1. Write or edit the file.
2. `httpc FILE --check [--env NAME] [--var N=V]` — sends nothing, needs no network. Fix every `error` before going on. Output is one `FILE:LINE:COLUMN: error|warning: message [code]` per line; exit 1 means an error, warnings do not fail.
3. Only run for real (`httpc FILE ...`) when the user wants requests sent. Running sends real traffic, may change data and may use secrets: **ask first for anything that is not a read (`GET`/`HEAD`/`OPTIONS`) against a server the user named**, and never point a file at a host the user did not mention.
4. Read the exit code and stderr (below), fix, repeat.

## The format

```
# a file variable applies from its line to the end of the file (no comment after the value)
@base = http://localhost:8080

# @name login
# @assert status == 200
# @assert body.$.token exists
POST {{base}}/login
Content-Type: application/json

{"user": "{{user}}", "id": "{{$uuid}}"}

###
# @name me
# @assert status == 200
GET {{base}}/me
Authorization: Bearer {{login.response.body.$.token}}
```

- `###` separates requests. Methods: GET POST PUT PATCH DELETE HEAD OPTIONS TRACE CONNECT QUERY. A bare URL is a GET. Headers follow the request line, a blank line starts the body. Indented `?a=1` / `&b=2` lines extend the URL.
- `# @name x` names a request (needed for chaining and `--request`). Other `#` / `//` lines are comments.
- Not supported (parse error or ignored with a warning): GRAPHQL/WEBSOCKET/GRPC requests, response handlers `> {% ... %}`. Do not write them.
- Body from a file: a body that is only `< ./path`; the path must stay inside the `.http` file's folder (other folders: `--allow-file-root DIR`). Multipart: `Content-Type: multipart/form-data; boundary=B` with literal boundary and `--B` part lines.
- `>> out.json` saves the response body (never overwrites), `>>! out.json` overwrites; they go after the body. This writes files: say so to the user.

### Variables

`{{name}}` works in URL, header values, body, file paths. An undefined name is an error, nothing is sent half-filled. Precedence: `--var` > `@name = value` lines > environment. Environment: `http-client.env.json` in the file's folder or a parent (`{"dev": {"base": "..."}}`, optional `"$shared"`), chosen with `--env dev`; `http-client.private.env.json` beside it holds secrets and overrides it. **Never print, paste or commit values from the private file, and put new secrets there, not in the `.http` file.**

Dynamic: `{{$uuid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`, `{{$randomInt 5 10}}`, `{{$random.integer(1, 9)}}`, `{{$random.alphanumeric(8)}}`, `{{$random.email}}`, offsets such as `{{$timestamp 1 d}}`. `{{$env.NAME}}` reads an OS variable only if allowed with `--allow-env NAME`; do not add that flag without the user's say-so.

### Chaining

After a named request ran, later ones read it: `{{login.response.body.$.token}}` (JSONPath subset: `$`, `.key`, `['key']`, `[0]`), `{{login.response.status}}`, `{{login.response.headers.X-Id}}`, `{{x.response.body.*}}` (whole body), `body.//xpath`, `body.regex:id=(\d+)`. `--request NAME` runs that request plus the named requests it reads from. Cookies are kept between requests of one run.

### Assertions

`# @assert <expr> <op> [expected]` before the request line; repeatable. `<expr>`: `status`, `headers.Name`, `body.$.path`, `body.*`. Operators: `== != < <= > >= contains !contains startsWith endsWith matches exists !exists` (`exists`/`!exists` take no expected value). Quote an expected value to compare text exactly; quote an expression that contains spaces. A request passes only if it completed and all assertions passed. A 4xx/5xx is *not* a failure unless an assertion says so or `--fail-on-http-error` is given.

### Settings

`# @timeout 5` (seconds, or `500ms`, `2m`; default 60s), `# @no-redirect`, `# @no-cookie-jar`, `# @proxy host:port`, before the request line.

## Options worth knowing

| Option | Use |
|---|---|
| `--check` | analyse only; allowed with `--env`, `--var` |
| `--list` | list requests (`1. name  GET url`), run nothing |
| `--env NAME` | pick an environment |
| `--request NAME\|N` | one request by name or 1-based index |
| `--var N=V` | override a variable (repeatable) |
| `--report-junit F`, `--report-json F` | machine-readable results for CI or for you to read |
| `--quiet`, `--output F` | no per-request output; raw body of one request to F |
| `--fail-on-http-error` | status >= 400 fails the request |
| `--timeout D` | default total time limit |

## Exit codes and diagnostics

`0` passed, `1` a request or assertion failed, a setup problem (missing file, unknown environment or request), a response save (`>>`) that failed, or `--check` found an error. **Exit 1 does not mean nothing was sent**: a POST may have taken effect and only its save or a later assertion failed, so read stderr before retrying anything that changes data; `2` usage error (bad option combination, nothing ran), `3` the run passed but a report/output file (`--report-*`, `--output`) could not be written. A summary line goes to stderr; `Saved response: PATH` too when `>>` fired.

Diagnostic codes from `--check`: `undefined-variable`, `unknown-request`, `malformed-reference` (errors in the reference); `invalid-dynamic-variable`, `circular-variable`, `parse-error` (errors); `request-not-run-yet`, `self-reference`, `duplicate-request-name`, `variable-defined-later`, `unterminated-reference`, `invalid-reference-text` (warnings — read them, they usually mean a real mistake). `--check` never reads values, so a name defined in the environment file counts as defined even if its value is empty.

## Reporting back

Say which file you ran, with which environment, the exit code and the failed assertions. Failure messages hide substituted values on purpose; do not try to recover them from the environment files. Reports may contain URLs with tokens: treat them as sensitive and do not commit them.
