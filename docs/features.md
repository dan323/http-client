# Features

A Postman-style HTTP client compatible with the JetBrains `.http` file format.
Currently available through the `httpc` command-line front end.

## `.http` files

Requests are separated by `###`.

```
# @name login
POST {{base}}/login
Content-Type: application/json

{"user": "{{user}}"}

###
GET {{base}}/me
```

- `# @name x` or `// @name x` names a request (needed for chaining and `--request`).
- Lines starting with `#` or `//` are comments.
- A bare URL (no method) means `GET`. A trailing `HTTP/1.1` is ignored. An unknown method (`GTE http://x`) is a parse error naming the line, with a "did you mean" hint for a one-letter typo; a method with no URL is also an error. `GRAPHQL`, `WEBSOCKET` and `GRPC` are accepted as method words but sent as plain HTTP requests. Indented `?a=1` / `&b=2` lines after the request line extend the URL, and an indented header line without `:` continues the previous header value. `> handler` / `<> file` lines are ignored with a warning only when they come after the body; earlier ones are body text. `<@ path` reads the file but does not substitute variables inside it (warning).
- Headers follow the request line; a header line without `:` is a parse error naming the line. A blank line starts the body.
- A body consisting only of `< ./path` (note the space; `<html>` is plain text) is read from that file, relative to the `.http` file.
  - **Security note (deviates from JetBrains, which does not confine):** `< path` and `<@ path` may only read files inside the `.http` file's folder, checked after `..` normalisation and after resolving symlinks (a symlink pointing out of the folder is rejected, as are absolute paths elsewhere). A rejected read fails that request with an error naming the path and how to allow it; no file contents are included. To allow more directories: CLI `--allow-file-root DIR` (repeatable); desktop app and IDE plugin `-Dhttpclient.allowFileRoots=DIR` or environment variable `HTTPC_ALLOW_FILE_ROOTS=DIR`, several separated by the platform path separator (`:`, or `;` on Windows). Roots from the property/env var must be absolute (a leading `~` is expanded); relative roots, empty values (also `--allow-file-root ""`), unparsable paths and filesystem roots such as `/` or `C:\` are rejected with an error naming the entry (shown as a failed request in the desktop app, in the tool window in the IDE, and as a usage error in the CLI); a root equal to your home directory only prints a warning. In the IDE the environment variable `HTTPC_ALLOW_FILE_ROOTS` is the practical choice (the property needs VM options). Residual risk: the file is opened without following a final symlink and re-verified, but someone with write access inside an allowed folder could still race the check by swapping a parent directory for a symlink.
- Response handlers (`> {% ... %}`, `> file`) and `<> file` redirects are not supported: they are not sent as body, and the parser reports a warning per ignored line (`HttpFile.warnings`; the CLI prints them on stderr).

## Variables

`{{name}}` is replaced in the URL, header values, body text and file-body paths.
An undefined variable is an error, so nothing is sent with a placeholder left in.

### Environments

Put `http-client.env.json` in the `.http` file's folder or any parent folder and pick one with `--env`. All front ends
(CLI, desktop, IntelliJ plugin) use the same rule: the nearest enclosing folder containing `http-client.env.json`
wins (searching upward); the `http-client.private.env.json` beside it is merged. A folder holding only the private file
counts. The `.http` file's own folder is always checked; the search then continues through parent folders but stops after
the first folder containing a `.git` entry, and never enters the user's home directory (or the system temp directory) or
anything above it, so a stray `~/http-client.env.json` cannot leak into unrelated projects (put the file in the project).
Running a request reads the env files only when a named environment is chosen (the CLI and the run path never read them otherwise); the desktop app also reads them when opening or saving a request, and the IDE plugin to fill its environment picker, so a malformed file can be reported even with no environment chosen. The choice is explicit: no
environment (variables from the file only; the CLI default) or a named one. A name that does not exist is an error,
never another environment. A JSON `null` value is treated as undefined; a malformed env file fails with an error
naming the file (and the position).

```json
// http-client.env.json
{ "dev": { "base": "http://localhost:8080", "user": "alice" } }
```

`http-client.private.env.json` uses the same shape for secrets; its values override the shared file.

### File variables

`@name = value` lines define variables inside the `.http` file. They apply to the requests from that line to the end
of the file; defining the same name again replaces the value from there on. File variables override environment
values, and a value may use `{{...}}` (environment variables, earlier file variables, dynamic variables, response
references). Only values written in the file are expanded: an environment value (or a response value) that contains
`{{` is sent as is, so environment values cannot reference other variables (`"api": "{{host}}/v1"` sends the
text `{{host}}/v1`); compose them with file variables instead. A variable that refers to itself reads the environment's value of that name (`@host = {{host}}/api`), and fails
with `Undefined variable: host` when there is none; a cycle through others (`@a = {{b}}`, `@b = {{a}}`) fails with
`Circular variable reference: a -> b -> a`, and a
value that needs more than 10,000 distinct expansions (or 100 nested levels) fails instead of hanging. Running a single request (`--request`,
"run this request") also runs the requests it reads responses from, including through file variables, `# @assert`
expressions and settings, and request names may contain dots (`auth.login`).

```
@host = http://localhost:8080
@user = {{$uuid}}

GET {{host}}/users/{{user}}
```

### Request settings

`# @setting [value]` (or `// @setting`) lines before a request line attach settings to it: for example
`# @timeout 5`, `# @no-redirect`, `# @no-cookie-jar`. The parser records them on the request
(`RequestTemplate.settings`); the following are honoured per request by the executor (carried as `HttpRequest.options`):

- `# @timeout 5`: maximum time for the whole exchange, response headers and body; on expiry the request is cancelled and fails with a timeout. Without `@timeout` there is no total limit (the default client only has a 30 s connect timeout). Interrupting the calling thread cancels the in-flight request and fails with a `CancellationException` (the interrupt flag is kept). A whole, positive number: plain numbers are seconds, or add `ms`, `s` or `m` (any case, `@timeout 500ms`). Decimals (`1.5s`), `0`, negatives and values that overflow are rejected. May use `{{vars}}`.
- `# @no-redirect`: do not follow HTTP redirects (the 3xx response is returned). An optional value `true` (default) or `false` (`# @no-redirect false` keeps redirects); it may use `{{vars}}`; any other value is an error.
- `# @proxy host:port`: send the request through that HTTP proxy. Forms: `host`, `host:port`, `http://host:port`, IPv6 as `[::1]:8080`; the port defaults to 80. Only plain HTTP proxies are supported: other schemes (`https://`, `socks://`) and credentials (`user:pass@host`) are rejected. May use `{{vars}}`.

The JDK client controls `Host`, `Content-Length`, `Connection`, `Expect` and `Upgrade` itself, so user-set headers with these names are dropped silently. With the session cookie jar on (the default), redirects are followed by the session: an explicit `Cookie`, `Authorization` or `Proxy-Authorization` header is dropped when a redirect leaves the origin (scheme, host, port) but kept on same-origin hops, where the jar also adds its matching cookies, and `@timeout` is one deadline across all hops. With `@no-cookie-jar` the JDK client follows redirects itself and its handling of explicit credential headers is JDK-version dependent (not guaranteed here).

Setting names are case-insensitive and unknown ones are ignored. Invalid values (including a blank `@proxy`) fail when the request is resolved with an `Invalid @timeout` / `@proxy` / `@no-redirect` error, before anything is sent. `JdkHttpExecutor` reuses one derived client per (redirect policy, proxy) and copies the injected client's connect timeout, SSL, authenticator, cookie handler, executor and HTTP version; request priority cannot be copied.

- `# @no-cookie-jar`: do not use the cookie jar for this request (see below). Same optional `true`/`false` value as `@no-redirect`.

### Cookie jar

Each `RequestSession` (one CLI run; in the desktop app and plugin one per file, environment and loaded variables, kept by the shared `RunController` so when you send one request, the named requests it chains on that already ran in the session, such as a login, are not sent again until the environment file changes or the chain is reset; the selected request itself, and every request of Send all, always run again) owns an in-memory `CookieJar` in `core`. `Set-Cookie` headers
of a response are stored, and later requests in the same session to a matching host and path get a `Cookie` header, so a login
request followed by an authenticated one just works. Sessions never share cookies and nothing is persisted to disk.
Supported: `Domain` (host-only when absent; a host cannot set cookies for an unrelated domain), `Path` (matched on the raw,
undecoded path), `Max-Age` (digits with optional `-`; `+5` or text is ignored; extreme values saturate), `Expires` (lenient RFC 6265
date parsing: `21 Oct 2015`, `21-Oct-2015`, 2-digit years, asctime; unparseable is ignored; `Max-Age` wins), `Secure`;
`Max-Age<=0` or a past `Expires` deletes. `# @no-cookie-jar` makes a request neither send nor store cookies.

Redirects: when the jar is in use, the session follows redirects itself, one hop at a time (the executor is told not to follow
any), so `Set-Cookie` on a `302` (the `POST /login` -> `302` flow) is stored and the `Cookie` header is recomputed for every hop's
URL. Up to 10 hops, then an error. `# @no-redirect` returns the first response (its cookies are still stored). 301/302 on `POST`
and 303 switch to `GET` without body; 307/308 keep method and body. An explicit `Cookie`, `Authorization` or
`Proxy-Authorization` request header is dropped when a redirect leaves the origin (scheme, host, port). Measured: the JDK
client itself does not forward manually set headers on redirects, so hop-by-hop handling is also what makes same-site hops
carry the cookie. The reported duration is the sum of all hops. With `@no-cookie-jar` the executor's own redirect handling applies.

Limitations: a `Cookie` header written in the request is sent as is (the jar adds nothing); there is no public-suffix list, only
single-label `Domain` values and a short built-in list of common two-label suffixes (`co.uk`, `com.au`, ...) are rejected,
and an IP host only matches exactly; `Secure` cookies are neither accepted from nor sent over plain http (no localhost exception),
and a plain-http response cannot overwrite a `Secure` cookie; a cookie over 4096 bytes is ignored, at most 50 per domain and
3000 in total (oldest first evicted); no SameSite handling. `@no-cookie-jar` cannot disable a `CookieHandler` configured
on an `HttpClient` injected into `JdkHttpExecutor` (that handler still sends and stores on its own).

### Assertions

`# @assert <expression> <operator> [expected]` lines before a request line check its response. They may repeat
and are kept apart from settings (`RequestTemplate.assertions`).

```
# @assert status == 200
# @assert headers.content-type contains json
# @assert body.$.id exists
# @assert body.$.n > 3
GET {{base}}/items/1
```

- `<expression>` is `status`, `headers.Name` or `body.<...>` (`body.*`, `body.$.json.path`, `body./xpath`,
  `body.regex:...`). Any other prefix (a typo such as `stauts` or `header.X`) is an invalid assertion, not a
  missing value. The expression ends at the first space; to use one with spaces, quote it:
  `# @assert "body.$.items[?(@.id == 1)]" exists`.
- `expected` is the rest of the line, inner spaces kept. In matching single or double quotes it is literal
  (`== ""` asserts an empty value, `== " a "` keeps the spaces) and `==`/`!=` compare as exact text. An unquoted
  empty expected value is invalid.
- Operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `contains`, `!contains`, `startsWith`, `endsWith`,
  `matches` (the whole value matches a regex; `.` also matches newlines; a pattern running over 2 seconds is
  aborted and reported as invalid), `exists`, `!exists`. `exists`/`!exists` take no expected value.
- Numbers are strict decimals (`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d{1,4})?`) compared exactly as `BigDecimal`.
  `==` holds when the texts are identical, or when both sides are numbers with the same value: `5 == 5.0` and
  `1e3 == 1000` pass, `1.10 == 1.1` passes (quote the expected value, `== "1.1"`, to compare text), while
  `007 == 7` fails (leading zeros are text), as do `NaN`/`Infinity` (plain text). `<`, `<=`, `>`, `>=` need numbers
  on both sides.
- A value that cannot be found (nothing matched) fails every operator except `!exists`, including `!=` and
  `!contains`. An XPath that evaluates to a string (`xpath:string(//a)`, `count(...)`) gives `""` rather than
  missing, so `exists` holds for it; use `== ""` to test for emptiness.
- `{{variables}}` and `{{name.response...}}` references are substituted before evaluating, exactly as in
  request text (environment, file variables, dynamic variables). An undefined one makes the assertion fail
  as invalid with "Undefined variable: x".
- A malformed assertion (including a bare `# @assert`) fails with an "invalid assertion" message. A trailing
  ` # comment` is stripped (a `#` preceded by whitespace and outside quotes). `# @assert` lines must sit before
  the request line; in a block that has no request they are discarded. Failure messages show at most 200
  characters of the actual value.
- When an assertion uses `{{variables}}` its resolved values are never shown: a failing comparison reports
  "expected value contains substitutions, not shown", while an invalid one (bad regex, non-numeric `<`, unknown
  operator) keeps its reason with the values left out.
- `RequestSession` is safe for concurrent use: runs are serialised (one request at a time per session) and
  interrupting the running thread stops `runAll` without sending the queued requests.
- `RequestSession.runAll` evaluates them and attaches one `AssertionResult` (passed, expected, actual, message) each to
  `RunOutcome.Success.assertions`. A request that throws reports `RunOutcome.Failure.assertionsNotRun`.
  `runChained` (single-request runs) evaluates only the requested request; its prerequisites run for their responses
  and their assertions are neither evaluated nor reported. Reused dependencies are not re-asserted.

### Run reports

`RunReport.from(outcomes)` (in `core`) builds a pure summary of a run: one entry per request (name, method, URL as
written in the file, status, duration, assertion results, assertions not run, error) plus totals and an overall
pass/fail. A request passes when it completed and all its assertions passed. Exporters: `JUnitXmlReport` (`core`,
plain string building with XML escaping; illegal XML characters are dropped) and `JsonRunReport` (`adapters`,
kotlinx-serialization, also parses a report back).

The CLI writes them with `--report-json FILE` and `--report-junit FILE`. Option values must be non-empty and not
start with `--`; repeating an option or giving both reports the same path is a usage error (exit 2). Parent
directories are created and files are written atomically (temp file + move), each report independently. Exit codes:
0 ok, 1 request/assertion failure, 2 usage, 3 a report could not be written (stderr says whether the run itself
passed). If the run cannot start (missing file, unknown environment or request, unexpected exception) a report with a
single failed `httpc` entry is still written so CI finds a file. Console output is unchanged.

JUnit XML: one `testcase` per request, so `tests`/`failures`/`errors` match the elements. All failed assertions of a
request are merged into one `failure` (one per line); a request that could not complete is an `error` (time 0). The
testcase `name` is `"<index>. <request name or METHOD url>"` (1-based position in the run) and `classname` is the
file name, so duplicate or unnamed requests stay distinct and stable. Prerequisites (requests run only to feed
`{{name.response...}}` chaining) are not asserted: they are omitted when they passed and kept as an `error` when they
failed; in JSON they are always listed with `"prerequisite": true`. An empty run reports `tests="0"`. Tabs and line
breaks in attributes are written as character references.

JSON: top-level `version` (currently 1); `JsonRunReport.parse` rejects a newer version. `passed` and `totals` (and each
entry's `passed`) are derived: written for consumers, ignored and recomputed by `parse`.

Caveats: a prerequisite or an unasserted request answering HTTP 500 still counts as passed (only transport errors and
failed assertions fail a run), so a green report does not mean "no 5xx". Reports contain URLs as written in the file
and error messages, which may include secrets (tokens in query strings); treat them as sensitive CI artifacts.

### Dynamic variables

Each occurrence is evaluated separately. A variable you define with the same name wins.

| Variable            | Value                      |
|---------------------|----------------------------|
| `{{$uuid}}`         | random UUID                |
| `{{$timestamp}}`    | Unix time in seconds       |
| `{{$isoTimestamp}}` | current time, ISO-8601 UTC |
| `{{$randomInt}}`    | integer from 0 to 999      |

More (names modelled on the JetBrains HTTP Client and Postman; see the notes below for what is ours):

| Variable                          | Value                                                              |
|-----------------------------------|--------------------------------------------------------------------|
| `{{$randomInt 5 10}}`             | integer from 5 to 10, both inclusive                               |
| `{{$random.integer(from, to)}}`   | integer, both ends inclusive                                       |
| `{{$random.float(from, to)}}`     | decimal, `from` inclusive, `to` exclusive                          |
| `{{$random.alphabetic(n)}}`       | `n` letters (`n` from 0 to 10000)                                  |
| `{{$random.alphanumeric(n)}}`     | `n` letters or digits                                              |
| `{{$random.hexadecimal(n)}}`      | `n` lowercase hex digits                                           |
| `{{$random.email}}`               | e.g. `k3j9x0aa@qwertz.com`                                         |
| `{{$random.uuid}}`                | same as `{{$uuid}}`                                                |
| `{{$timestamp 1 d}}`              | Unix seconds, shifted by an offset                                 |
| `{{$isoTimestamp -2 h}}`          | ISO-8601 UTC, shifted by an offset                                 |
| `{{$env.NAME}}`                   | OS environment variable `NAME`, only if explicitly allowed (below) |
| `{{$processEnv NAME}}`            | alias of `{{$env.NAME}}`                                           |

- **Offsets** are `<amount> <unit>` after `$timestamp` / `$isoTimestamp`: a whole number with an optional sign
  (`1`, `-2`, `+5`) and one of `ms`, `s`, `m` (minutes), `h`, `d`, `w`, `M` (calendar months), `y` (calendar years);
  the space between them is optional (`90s`). Units are case-sensitive (`m` minutes vs `M` months). Month and year
  offsets are calendar arithmetic in UTC and clamp to the end of a shorter month (31 Jan + 1 M = 29 Feb 2024).
  The syntax is loosely based on JetBrains' offsets and may not match it exactly; JetBrains' `$datetime` / `$localDatetime` are not supported.
- Arguments in `$random.*(...)` are comma separated; spaces are allowed. Wrong argument counts, non-numbers,
  `from` greater than `to` (and, for `$random.float`, equal to it), an unknown offset unit or a length outside 0..10000 fail (in an `# @assert` line that
  makes only that assertion invalid) with
  `Invalid dynamic variable {{...}}: <reason>`.
- An unknown `$name` or an unset `$env.NAME` fails as `Undefined variable: <name>`.
- **`$env` is opt-in.** A `.http` file you download or open could otherwise send `AWS_SECRET_ACCESS_KEY` or any
  token in your environment to a server of its choosing, so no variable is readable until you allow its name
  (exact match; everything else fails with `Invalid dynamic variable {{$env.X}}: environment variable 'X' is not
  allowed; <how to allow it>`). An allowed name that is not set fails as `Undefined variable: $env.X`.
  Prefer `http-client.env.json` / `.private.env.json` for secrets; use `$env` only for values you are happy to
  expose to the files you run. How to allow names:
  - CLI: `--allow-env NAME[,NAME]` (repeatable), e.g. `httpc api.http --allow-env API_TOKEN,HOME`; the property and variable below are honoured
    too and merged with the flag.
  - Desktop app and IntelliJ plugin (no UI for this yet): the system property `-Dhttpclient.allowEnv=NAME,NAME`
    (for the IDE add it under Help | Edit Custom VM Options) and/or (the two lists are merged) the environment
    variable `HTTPC_ALLOW_ENV=NAME,NAME` of the process that starts the app/IDE. Restart to apply.
  - Library users: `SystemEnvironmentVariables(setOf("NAME"))` from `adapters`; `core` callers that pass nothing
    see no variables (the `EnvironmentVariables` port).
  - Environment variables of a GUI-launched IDE may differ from your shell.
- Values read through `$env` are used exactly where you write them and are inserted as plain text (they are not
  scanned for `{{...}}` again). The desktop/plugin reports and CLI output show the request as written in the
  file (with `{{$env.X}}` unresolved), not the resolved request, and error messages name the variable, never its
  value. The server's response may of course echo what you sent.
- **Randomness and what is ours.** `$uuid`, `$random.uuid` and the `$random.*` family use a cryptographically strong
  generator (`SecureRandom`), which is stronger than we know JetBrains to guarantee; this was our choice, not
  something taken from JetBrains. The offset syntax (`1 d`, `-2 h`, units `ms s m h d w M y`) is also our own design,
  loosely inspired by JetBrains' `$timestamp`/`$isoTimestamp` offsets, and may not match it exactly.
  `$random.float` is printed as plain decimal, never as `1.0E10`.
- Naming: to our knowledge the JetBrains HTTP Client reads process environment with `{{$processEnv NAME}}`;
  `{{$env.NAME}}` is the form used here and `$processEnv NAME` is accepted as an alias. We could not verify this
  against JetBrains' current documentation, so check before moving files between tools. In JetBrains the allowlist
  does not exist, so a file using `$processEnv` there will not need `--allow-env` but will here.
- Only `$` followed by a letter starts a dynamic variable; `{{$ x}}`, `{{$x := 1}}` and references split over
  several lines are left untouched, as before.
- For tests, `DynamicVariables(random, clock, env)` makes randomness, time and environment injectable.

## Request chaining

Requests run in file order. Once a **named** request has run, later requests can use its response:

| Reference                         | Value                                |
|-----------------------------------|--------------------------------------|
| `{{login.response.body.$.token}}` | value at a JSONPath in the JSON body |
| `{{login.response.body.*}}`       | whole body                           |
| `{{login.response.headers.X-Id}}` | header value (case-insensitive); the first one if repeated |
| `{{login.response.headers.Set-Cookie[1]}}` | the second value of a repeated header (0-based; missing when out of range) |
| `{{login.response.headers.Set-Cookie[*]}}` | all values of the header, joined with a newline |
| `{{login.response.status}}`       | status code                          |
| `{{page.response.body.//a/@href}}` | XPath over an XML body (also `body.xpath:count(//a)`) |
| `{{page.response.body.regex:id=(\d+)}}` | regex over the body: group 1 if present, else the whole match |

The same expressions (via `ResponseExtractor` in `core`) are what assertions will use.

The body is decoded with the `charset` of the response's `Content-Type` (UTF-8 when absent or unknown). XPath is
evaluated with secure processing (no DOCTYPE, no external access, no extension functions) and stops after 2 seconds,
finding nothing.

Failure messages (also in `--report-json` / `--report-junit` and on stderr) never contain substituted variable
values: a value that appears in an error text (for example the resolved URL in an invalid-URI error) is shown as
`{{name}}`. Values shorter than 3 characters are not replaced.

Supported JSONPath subset: `$`, `.key`, `['key']`, `[index]`. Objects and arrays are returned as JSON text.
Referencing a request that has not run, or a path that does not exist, is an error.

## Command line

```
httpc <file.http> [--env NAME] [--request NAME|INDEX] [--list]
```

| Option        | Effect                                               |
|---------------|------------------------------------------------------|
| `--env NAME`  | use variables from `NAME` in `http-client.env.json`  |
| `--request X` | run the request named `X`, or the Nth (1-based), plus the requests it chains on |
| `--list`      | list requests and exit                               |

For each request the output is the status line with timing, the response headers, then the body.
After a response, each assertion is printed as `PASS ...` or `FAIL ... -- message`; for a request that failed its assertions are listed as `NOT RUN ...`.
A total line `Assertions: N passed, M failed, K not run` follows the run. With `--request`, only the selected request's assertions run, not its prerequisites.
Exit codes: `0` success, `1` a request or setup failed or an assertion failed, `2` bad arguments.

Run with `./gradlew :cli:run --args="requests.http --env dev"`, or build a launcher with
`./gradlew :cli:installDist` (output in `cli/build/install/httpc/bin`).

`--request` also runs the named requests the selected one reads responses from (transitively), in file order, so chaining keeps working.

## Desktop app

A standalone window for Windows, macOS and Linux: file editor, request list, environment picker, and a response viewer. See [desktop/README.md](../desktop/README.md).

## IntelliJ plugin

Works in any IntelliJ-based IDE, including Community (it depends only on the platform module).

- Open a `.http` file, put the caret in a request and press **Ctrl+Alt+Enter** (or use *Run HTTP Request* in the editor context menu).
- *Select HTTP Environment* in the context menu picks the environment from `http-client.env.json`.
- Results appear in the **HTTP Response** tool window. Chained prerequisites run first, as in the CLI.

- `.http` files get syntax highlighting (methods, URLs, header names, `{{variables}}`, comments, `###` separators, `< file` bodies).
- A green run icon in the gutter on each request line runs that request.
- JSON response bodies are pretty-printed.

The response is shown as plain text for now.
Do not install it next to the bundled HTTP Client plugin of IntelliJ Ultimate, since both claim `.http` files.

## Not yet implemented

Response handlers, assertion display in the desktop app and plugin, and authentication helpers.
