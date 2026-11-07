# Features

A Postman-style HTTP client compatible with the JetBrains `.http` file format.
Front ends: the `httpc` command line, a desktop app and an IntelliJ plugin, all built on the same `core` and `adapters`.

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
- A bare URL (no method) means `GET`. A trailing `HTTP/1.1` is ignored. An unknown method (`GTE http://x`) is a parse error naming the line, with a "did you mean" hint for a one-letter typo; a method with no URL is also an error. `GRAPHQL`, `WEBSOCKET` and `GRPC` (request types of the JetBrains format that are not supported) are also a parse error naming the line ("GRPC requests are not supported (only HTTP methods are sent)"), so the file does not run and nothing is sent as plain HTTP. Indented `?a=1` / `&b=2` lines after the request line extend the URL, and an indented header line without `:` continues the previous header value. `> handler`, `>> file` and `<> file` lines count only when they come after the body; earlier ones are body text. `<@ path` reads the file but does not substitute variables inside it (warning).
- Headers follow the request line; a header line without `:` is a parse error naming the line. A blank line starts the body.
- A body consisting only of `< ./path` (note the space; `<html>` is plain text) is read from that file, relative to the `.http` file.
  - **Security note (deviates from JetBrains, which does not confine):** `< path` and `<@ path` may only read files inside the `.http` file's folder, checked after `..` normalisation and after resolving symlinks (a symlink pointing out of the folder is rejected, as are absolute paths elsewhere). A rejected read fails that request with an error naming the path and how to allow it; no file contents are included. To allow more directories: CLI `--allow-file-root DIR` (repeatable); desktop app and IDE plugin `-Dhttpclient.allowFileRoots=DIR` or environment variable `HTTPC_ALLOW_FILE_ROOTS=DIR`, several separated by the platform path separator (`:`, or `;` on Windows). Roots from the property/env var must be absolute (a leading `~` is expanded); relative roots, empty values (also `--allow-file-root ""`), unparsable paths and filesystem roots such as `/` or `C:\` are rejected with an error naming the entry (shown as a failed request in the desktop app, in the tool window in the IDE, and as a usage error in the CLI); a root equal to your home directory only prints a warning. In the IDE the environment variable `HTTPC_ALLOW_FILE_ROOTS` is the practical choice (the property needs VM options). Residual risk: the file is opened without following a final symlink and re-verified, but someone with write access inside an allowed folder could still race the check by swapping a parent directory for a symlink.
- Response handlers (`> {% ... %}`, `> file`) are not supported: they are not sent as body, and the parser reports a warning per ignored line (`HttpFile.warnings`; the CLI prints them on stderr). `>> file`, `>>! file` and `<> file` are supported, see "Saved responses".

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

### Editor diagnostics (analysis)

`VariableAnalysis` (`core`, pure and IO-free) checks the `{{...}}` of a `.http` text without running anything, for the
editors (desktop and plugin use it in later tasks). `VariableAnalysis.analyze(text, environmentNames)` takes the
text and the *names* of the available environment variables (values never enter the analysis; pass the union of
all environments for a lenient editor, nothing for "no environment") and offers:

- `diagnostics`: `Diagnostic(code, severity, message, range)`; `range` is a `SourceRange` in UTF-16 chars (offset, length,
  0-based line and column; tabs and CRLF count as in the text). Messages name variables and requests, never values.
- `knownVariablesAt(offset)`: file variables defined above, environment variables, `name.response.` prefixes of the
  requests above, and the dynamic variable templates (`$uuid`, `$timestamp`, `$random.integer(from, to)`, `$env.NAME`, ...).
- `findUsages(name)`, `findVariableUsages`, `findRequestUsages`, `occurrenceAt(offset)`: every use, `@x =` definition,
  `# @name` declaration and `{{name.response...}}` prefix, each range covering exactly the name (for rename).

| Code | Severity | Meaning |
|---|---|---|
| `undefined-variable` | error | not a file variable, environment variable or known dynamic variable |
| `unknown-request` | error | `{{x.response...}}` and no request is named `x` |
| `request-not-run-yet` | warning | the request `x` first appears later in the file (works at run time only if it already ran in the same session) |
| `self-reference` | warning | a request reads its own response (works on a later run in the same session, e.g. a polling request) |
| `malformed-reference` | error | `x.response` with nothing after it, an unknown part (not `status`, `headers.Name`, `body...`) or an empty `headers.`/`body.` |
| `invalid-dynamic-variable` | error | bad arguments, e.g. `$random.integer(5, 1)` |
| `circular-variable` | error | file variables that refer to each other (on the final value of each name) |
| `unterminated-reference` | warning | `{{` with no `}}` after it |
| `invalid-reference-text` | warning | `{{ a b }}`: not a reference, sent as written |
| `duplicate-request-name` | warning | the later `# @name` repeats an earlier one |
| `variable-defined-later` | warning | an `@a = {{b}}` value uses a file variable defined below it |
| `parse-error` | error | the parser rejected a line (its message, on that line); one per `###` block |

A file that does not parse is still analysed: each rejected line is reported as `parse-error` and left out (the offsets of the
rest do not change), so completion, find-usages and the other diagnostics keep working while a request is half typed. The
request that failed has no `name.response.` prefix and its references are not scanned. Only a file with more than 50 bad
lines is reported as parse errors alone.

It follows the run-time rules: the same reference pattern and lookup order as `VariableResolver` (a file variable
beats the environment in request text, and in a value only when defined above it; a variable naming itself reads the
environment), and a response reference needs an earlier request with that name. Scanned: request line and query
lines, header values (not names), body text, the *path* of a `< path` body (not the file), the path of a `>> path` / `>>! path` save (a `<>` link path is not resolved, so it is not scanned), file variable values,
`# @assert` expressions (without a trailing ` # comment`) and the values of `@timeout`, `@no-redirect`,
`@no-cookie-jar`, `@proxy`. Not scanned: comments, `# @name`, other settings, trailing response handlers.
Not decidable statically, so never flagged: whether `body.$.x` finds anything, and `$env.X` / `$processEnv X`
(allow-list and value are run-time matters). Duplicate request names resolve to the latest response at run time. A
response reference inside a file variable value is only checked for an existing request name, because the request that
uses the variable is not known. Analysis time is linear in the text size.

### Request settings

`# @setting [value]` (or `// @setting`) lines before a request line attach settings to it: for example
`# @timeout 5`, `# @no-redirect`, `# @no-cookie-jar`. The parser records them on the request
(`RequestTemplate.settings`); the following are honoured per request by the executor (carried as `HttpRequest.options`):

- `# @timeout 5`: maximum time for the whole exchange, response headers and body; on expiry the request is cancelled and fails with a timeout. Without `@timeout` the default total timeout applies: 60 s, or the CLI `--timeout`, or the system property `httpclient.timeout` / environment variable `HTTPC_TIMEOUT` (same duration format; the desktop app and the plugin read the property and the variable; a bad value is reported when a request runs, never replaced by the default). The client also has a 30 s connect timeout. There is no way to disable the limit: use a long `@timeout` for slow endpoints. Interrupting the calling thread cancels the in-flight request and fails with a `CancellationException` (the interrupt flag is kept). A whole, positive number: plain numbers are seconds, or add `ms`, `s` or `m` (any case, `@timeout 500ms`). Decimals (`1.5s`), `0`, negatives and values that overflow are rejected. May use `{{vars}}`.
- `# @no-redirect`: do not follow HTTP redirects (the 3xx response is returned). An optional value `true` (default) or `false` (`# @no-redirect false` keeps redirects); it may use `{{vars}}`; any other value is an error.
- `# @proxy host:port`: send the request through that HTTP proxy. Forms: `host`, `host:port`, `http://host:port`, IPv6 as `[::1]:8080`; the port defaults to 80. Only plain HTTP proxies are supported: other schemes (`https://`, `socks://`) and credentials (`user:pass@host`) are rejected. May use `{{vars}}`.

The JDK client controls `Host`, `Content-Length`, `Connection`, `Expect` and `Upgrade` itself, so user-set headers with these names are dropped silently. With the session cookie jar on (the default), redirects are followed by the session (also without a jar, see Redirects below): an explicit `Cookie`, `Authorization` or `Proxy-Authorization` header is dropped when a redirect leaves the origin (scheme, host, port) but kept on same-origin hops, where the jar also adds its matching cookies, and `@timeout` is one deadline across all hops. With `@no-cookie-jar` redirects are followed the same way, only without reading or storing cookies: the JDK client is never left to follow redirects, so the credential handling is the same on every JDK.

Setting names are case-insensitive and unknown ones are ignored. Invalid values (including a blank `@proxy`) fail when the request is resolved with an `Invalid @timeout` / `@proxy` / `@no-redirect` error, before anything is sent. `JdkHttpExecutor` sends with `Redirect.NEVER` clients (redirects are followed by `RedirectFollower`), reusing one derived client per proxy when the injected client needs one or a request names an `@proxy`, and copies the injected client's connect timeout, SSL, authenticator, cookie handler, executor and HTTP version; request priority cannot be copied.

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
`Proxy-Authorization` request header is dropped when a redirect leaves the origin (scheme, host, port). The JDK
client is never asked to follow redirects (its forwarding of such headers varies by JDK version; `JdkHttpExecutor` itself follows
through the same code), so this holds on every JDK. With `@no-cookie-jar` the jar is only skipped (nothing read or stored) and explicit
credential headers are kept on same-origin hops. Only these three headers are stripped: other custom headers such as `X-API-Key` still cross origins on a redirect, so do not put a secret in one if the server may redirect elsewhere. 307/308 re-send the request body to the new origin (as the JDK and curl do). The reported duration is the sum of all hops.

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
pass/fail. A request passes when it completed and all its assertions passed. Exporters, both in `adapters`:
`JUnitXmlReport` (plain string building with XML escaping; illegal XML characters are dropped) and `JsonRunReport`
(kotlinx-serialization, also parses a report back). Files are written through the `ReportWriter` port (`core`),
implemented by `AtomicReportWriter` (`adapters`).

The CLI writes them with `--report-json FILE` and `--report-junit FILE`. Option values must be non-empty and not
start with `--`. These are usage errors (exit 2, nothing is run or written): repeating an option, giving both reports
the same path, a report path that is the input `.http` file (also through `..` or a symlink; it would overwrite it),
and combining a report option with `--list` (nothing runs, so there is no report). Parent directories are created
and files are written atomically (temp file + move), each report independently. New report files are owner-only
(`rw-------`, POSIX) because they may contain secrets; replacing an existing report keeps that file's permissions.
Exit codes, by precedence: 2 usage; 1 the run failed (request error, failed assertion, or the run could not start),
even if a report could not be written (the write error is still printed); 3 the run passed but a report could not
be written; 0 otherwise. The pass/fail decision is `RunReport.passed`. If the run cannot start (missing file,
unknown environment or request, unexpected exception) a report with a
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

### Saved responses

After the request (and after a `> {% ... %}` handler, which is parsed but ignored), as in the JetBrains plugin:

```
GET {{host}}/orders/1

>> out/order-{{id}}.json
<> order.json
```

- `>> path` saves the response body to `path`; it never overwrites. Like JetBrains, an existing name gets a numeric
  suffix before the extension: `out.json`, `out-1.json`, `out-2.json`. `>>! path` overwrites.
- `<> path` links the request to its latest stored response (see below).
- Order: JetBrains writes the handler, then `>>`, then `<>` lines; this parser accepts the `>>`/`<>` lines in any order
  but only after the body: a `>>`/`>>!`/`<>` line is a directive only in the trailing region, i.e. when every non-blank
  line after it is also a directive or a response handler. A line starting with `>> ` followed by more body text is body
  (`>>x` without a space never is a directive). Ambiguity, the same as in JetBrains: a last body line that starts with
  `>> ` (`hello` then `>> quoted reply`) is read as a directive, so existing files keep working; to avoid surprises the
  parser adds a warning when such a line directly follows the body (no blank line) and its path contains whitespace
  (what prose looks like), and a blank line before the directive removes both the doubt and the warning. To keep a
  line like that as body text, put more body text after it.
  At most one `>>`/`>>!` per request (JetBrains allows one): a second is a parse error with its line number. Several `<>`
  lines are fine (JetBrains accumulates them). An empty path is a parse error naming the line.
- The path may contain `{{variables}}`, resolved when the request runs. It is relative to the `.http` file; paths with
  spaces are fine, `\` counts as a separator on every platform. Names that cannot exist on Windows are refused on every
  platform, so a project shared between systems does not save files another system cannot hold: reserved device names
  (`CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9`, also with an extension), a segment ending in a dot or a space, and
  the characters `< > : " | ? *` and control characters (which also rules out `file.txt:stream`).
- Confinement is the one of `< path` bodies (`FileAccessPolicy`: the `.http` file's folder plus the allowlist): `..`
  out of the allowed folders, absolute paths (`/x`, `\x`, `C:\x`), symlinks leading out, a final symlink, a folder,
  the `.http` file itself and (for `>>!`) a read-only file are refused. Missing parent folders are created, only
  inside the allowed roots. Files are written atomically, owner-only on POSIX (an overwritten file keeps its
  permissions), byte for byte (no charset conversion); an empty body gives an empty file. Concurrent saves of the same
  name end up in different files (`>>`). The port is `ResponseSaver` (`core`), `LocalResponseSaver` (`adapters`).
- A save happens after the assertions are evaluated, also when they failed, but not when the request itself failed.
  A rejected or failed save fails only the save: the request's result stays, `RunOutcome.Success.saveError` holds the
  message (names the path, hides substituted values, never contains the body) and `savedTo` the path written.
  Paths are not part of reports. The path shown after a successful save (`savedTo`, the CLI line) follows the same rule
  as error messages: values substituted into `{{variables}}` (3 characters or more) come back as `{{name}}`, so
  `>> out/{{token}}.json` prints `out/{{token}}.json`, never the token. The file on disk has the real name.
- The temp file (random name, owner-only) is written in the final, already verified folder; `>>!` is one atomic rename
  inside that folder, and `>>` hard-links the finished temp file to the first free name (a link fails if the name exists,
  so nothing is clobbered and no empty or partial file is ever visible), then removes the temp name (without hard links a
  plain move without replace is used). Right before publishing, the folder's real path must still be the verified one
  and the temp file must be in it. If a folder was swapped for a symlink after validation, the source (named by its random
  temp name) does not exist at the new location, so the rename/link fails and nothing outside is touched, also for `>>!`.
  Residual race (same family as `< path` reads; the JDK has no openat): an attacker who can write inside the allowed
  folders, can observe the random temp name and swaps the folder in the microseconds between the last check and the
  syscall, while placing a file of that name at the new location, can still make one save land outside; that is detected
  afterwards, a `>>` file is removed, a `>>!` replacement cannot be restored and the error says so.
- The path is validated exactly as written: leading or trailing whitespace is refused (never trimmed into a different
  path).
- Bare `RequestSession.run` does not save or link; `>>`/`>>!`/`<>` are applied by `runAll`/`runChained`, once per request.
  A `>> {{other.response...}}` path makes `other` a prerequisite that runs first (also through file variables).
  Cancellation or interruption inside a save or link is not reported as a save error: it stops the session.
- CLI: a `--report-*` / `--output` file that is the same file as a response saved in this run (symlinks and case aliases
  included) is not written, an error is printed and the saved response is kept. Corrupt-history warnings go to stderr
  of the run.
- Prerequisites: a request that only runs to feed `{{name.response...}}` to the request you asked for (`@import`/chained
  dependencies) is sent like any other, so its own `>>` and `<>` directives fire too (it saved its `pre.json`). A
  prerequisite that is reused instead of re-sent does not save or link again.
- Desktop app and plugin show `Saved to PATH` (or the save/link error) under the status of the response; the desktop
  app passes a `FileHistoryStore` like the CLI and the plugin, so `<>` links are recorded there too.
- CLI: `Saved response: PATH` (relative to the `.http` file) on stderr, also with `--quiet`; a failed save prints
  `Error: Cannot save response to ...` on stderr and makes the exit code 1.

`<> path` links: JetBrains rewrites the `.http` file to add a `<>` line per stored response. This tool deliberately
never edits your `.http` file. The `<> path` text is kept as written (`RequestTemplate.responseLinks`, the path itself
is not used); instead, after a run of a request that has a `<>` line, its latest response is recorded in the history
index (`HistoryStore.recordLinked`), and `ResponseLinks.latest(store, httpFile, request)` returns the latest stored
response of that request (`HistoryStore.latestLinked`). A request is identified by its `# @name` (duplicate names
share one link: the newest run wins) or, unnamed, by the line where it starts (so adding lines above it breaks the
link). Recording is opt-in: `SessionFactory(history = FileHistoryStore())` (or any `HistoryStore`, with an injectable
root); the CLI and the plugin pass it, so the response of a linked request lands in `.http-history/`. The history keeps
the newest 50 runs per file, and additionally the newest record of every `<>` key even when it is older than that, so a
link keeps resolving however often other requests run (at most one extra run folder per distinct linked request; an
entry whose request lost its `<>` line or was removed stays until you delete it from `.http-history/`). Only a record whose body can still be read
protects its key from pruning. Entries written
before keys existed carry no key and are pruned as ordinary runs. Opening the linked response in
the desktop app or the plugin is not implemented yet.

### Run history store

`HistoryStore` (port in `core`) remembers past runs of a `.http` file: `record` (a `RunReport` plus the response of
each request), `list` (newest first), `read`, `readResponse` and `latestResponse` (by request name). Only `<>` links use it
today (see "Saved responses"); the CLI, desktop app and plugin do not record ordinary runs yet. `FileHistoryStore` (`adapters`)
keeps it in `.http-history/<file name>/` next to the `.http` file; the folder is injectable
(`FileHistoryStore.underRoot(dir)` for a shared root, or any `(Path) -> Path`).

Layout: one folder per run, named by its id (`20261106T101500123Z-n0000-a1b2c3d4`: UTC time, a sequence number within the millisecond so equal-time runs keep their recorded order, random suffix; ids from before the sequence existed stay readable and sort first), holding `run.json`
(version, id, timestamp, the report in the `JsonRunReport` format, and per response: status, headers, duration,
body size, `bodyTruncated`) and `<index>.body` (raw bytes). `run.json` is written last; a folder without it is
incomplete and ignored (removed after an hour). There is no shared index file, so two processes recording at once
cannot lose each other's runs; all files are written atomically.

- Retention: the newest 50 runs per file are kept (`maxRuns`); older ones are deleted when recording.
- Bodies are stored in full up to 5 MB (`maxBodyBytes`); larger ones are cut, with `bodyTruncated = true` and the real
  size in `originalBodySize`.
- Confinement: only the history folder is written. Body files are named by index, never by request name; the `.http`
  file name used as folder name is sanitized (separators, Windows-illegal characters, `.`/`..`, reserved names such
  as `CON`, trailing dots, length over 80); run ids are validated before use; symlinked entries are not followed.
- Corrupt, foreign or newer-version entries are skipped with a warning, never fatal.
- Secrets: stored responses are raw (tokens, cookies, personal data). On POSIX, folders are `rwx------` and files
  `rw-------` from creation (Windows uses the default ACLs). Add `.http-history/` to your `.gitignore` (this repository
  does) and never share the folder.

### Authorization shortcuts

As in the JetBrains HTTP Client, `Authorization: Basic` accepts plain credentials, which are Base64-encoded (UTF-8) when the request is sent:

```
Authorization: Basic {{user}} {{pass}}
Authorization: Basic {{user}}:{{pass}}
```

Variables resolve first. Rule: after `Basic`, a value that contains `:` or has two or more whitespace-separated tokens is plain credentials (user is the text before the first `:`, or the first token; everything after is the password, so passwords may contain `:` and spaces; an empty password `user:` is fine). A single token without `:` is taken as already encoded and sent unchanged, as is every other scheme (`Bearer {{token}}` needs no shortcut: it is just a header). The header name and the `Basic` keyword are case-insensitive, and every `Authorization` header of a request is handled on its own. Limitation: a username without a password written as `Basic user` is read as an already-encoded token; write `Basic user:`.

The encoded value, the `user:password` text and the password are kept out of failure messages like variable values (shown as `{{Authorization}}` or the variable name). Not implemented: `Digest` credentials and `{{$auth.token(...)}}` / OAuth helpers.

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

The same expressions (via `ResponseExtractor` in `core`) are what assertions use (see Assertions).

The body is decoded with the `charset` of the response's `Content-Type` (UTF-8 when absent or unknown). XPath is
evaluated with secure processing (no DOCTYPE, no external access, no extension functions) and stops after 2 seconds,
finding nothing. The evaluation itself cannot be interrupted, so the number of evaluations is capped instead: at most 4 run
at a time in the whole process (timed-out ones count until they really end). While all 4 are busy a new XPath
evaluation fails fast and finds nothing, like a timeout. Regex is stopped after 2 seconds as well.

Failure messages (also in `--report-json` / `--report-junit` and on stderr) never contain substituted variable
values: a value that appears in an error text (for example the resolved URL in an invalid-URI error) is shown as
`{{name}}`. Values shorter than 3 characters are not replaced.

Supported JSONPath subset: `$`, `.key`, `['key']`, `[index]`. Objects and arrays are returned as JSON text.
Referencing a request that has not run, or a path that does not exist, is an error.

## Command line

```
httpc <file.http> [--env NAME] [--request NAME|INDEX] [--allow-env NAME[,NAME]] [--allow-file-root DIR] [--list]
                  [--report-json FILE] [--report-junit FILE] [--quiet] [--output FILE] [--var NAME=VALUE] [--timeout DURATION]
```

| Option        | Effect                                               |
|---------------|------------------------------------------------------|
| `--env NAME`  | use variables from `NAME` in the nearest `http-client.env.json` (see Environments); without it no environment is used |
| `--request X` | run the request named `X`, or the Nth (1-based), plus the requests it chains on |
| `--allow-env N` | let `{{$env.N}}` read these OS environment variables (repeatable) |
| `--allow-file-root DIR` | also let `< path` bodies be read from `DIR` (repeatable) |
| `--list`      | list requests and exit (cannot be combined with `--report-*`) |
| `--report-json FILE` / `--report-junit FILE` | write a run report (see Run reports) |
| `--quiet`     | no per-request output on stdout (see below) |
| `--output FILE` | write the response body of the single selected request to `FILE` |
| `--var NAME=VALUE` | set a variable, overriding file and environment variables (repeatable) |
| `--timeout DURATION` | total time limit for requests without `# @timeout`: `30`, `500ms`, `45s`, `2m` (default 60 s; cannot be combined with `--list`) |

For each request the output is the status line with timing, the response headers, then the body.
After a response, each assertion is printed as `PASS ...` or `FAIL ... -- message`; for a request that failed its assertions are listed as `NOT RUN ...`.
A total line `Assertions: N passed, M failed, K not run` follows the run. With `--request`, only the selected request's assertions run, not its prerequisites.
**Summary line:** after a run the CLI prints `3 requests, 2 passed, 1 failed in 1.2s` to **stderr** (so stdout stays the response data, e.g. when piped); it counts every request that ran, prerequisites included, and a request passes when it completed and its assertions held. It is not printed for `--list` or when the run cannot start.

**`--quiet`:** nothing per request is printed on stdout (no `###` heading, status, headers, body, `PASS`, `NOT RUN` or the `Assertions:` total). Failed assertions are printed to stderr as `FAIL ...`, request errors as `Error: ...`, and the summary line still appears, so a quiet run says what failed. Exit codes are unchanged.

**`--output FILE`:** after the run, the body of the selected request's response is written to `FILE` (decoded with the response charset, then written as UTF-8; binary bodies are therefore not byte-exact). It needs exactly one selected request: use `--request`, or a file with one request; otherwise it is a usage error (exit `2`, discovered before anything is sent). Prerequisites are never written. Nothing is written when that request fails. It goes through the `ReportWriter` port and `AtomicReportWriter` like reports: parent folders are created, the write is atomic and a new file is owner-only (`rw-------`, POSIX) because bodies may contain secrets. `FILE` must differ from the input `.http` file and from the report paths (also through `..`, symlinks or case-only differences) and cannot be repeated or combined with `--list`. An unwritable output counts like an unwritable report (exit `3` on a passed run, `1` on a failed one).

**`--var NAME=VALUE`:** the name is everything before the first `=` and must be non-empty without whitespace or braces; the value may be empty or contain `=`. Repeating a name is a usage error. Precedence: `--var` beats file variables (`@name = value` lines), which beat the environment file. The resolver prefers file variables over the variable map, so the CLI removes the overridden names from the file variables first; other file variables that mention them then see the `--var` value. Values are treated like any resolved variable: they are replaced by `{{name}}` in failure messages, and usage errors never echo the value. `--quiet` and `--var` cannot be combined with `--list`.

Exit codes, by precedence: `2` bad arguments (usage is printed, nothing runs); `1` a request or setup failed (missing file, unknown environment or request), an assertion failed, or any other error; `3` the run passed but a requested report or output file could not be written; `0` otherwise. Parser warnings (ignored response handlers, see above) go to stderr as `warning: FILE: ...`; request failures go to stderr as `Error: ...`.

Run with `./gradlew :cli:run --args="requests.http --env dev"`, or build a launcher with
`./gradlew :cli:installDist` (output in `cli/build/install/httpc/bin`).

`--request` also runs the named requests the selected one reads responses from (transitively), in file order, so chaining keeps working.

## Desktop app

A standalone window for Windows, macOS and Linux: file editor, request list, environment picker, and a response viewer. See [desktop/README.md](../desktop/README.md).

- The environment choice is explicit: "none" (default; variables from the file only) or a name from the nearest `http-client.env.json`. A name that vanished is reported as an error, never replaced.
- One session is kept per file, environment and loaded variables, so **Send** on a chained request does not resend a login that already ran (reused prerequisites are not listed). Editing the text does not drop it; changing the environment file does. **Forget responses** drops all sessions so the next Send reruns the chain. **Send all** and the selected request always run again.
- Only one run at a time: while it runs, editing, saving, opening and creating files are refused. A run first saves the file; a save error stops the run and stays visible.
- **Remembered between runs** (`settings.json` in the user config folder: `$XDG_CONFIG_HOME/http-client` or `~/.config/http-client` on Linux, `~/Library/Application Support/http-client` on macOS, `%APPDATA%\http-client` on Windows): the last opened file, the last environment per file (by real path), the window size, position and maximized state, and the 10 most recent files (**Recent files** button, most recent first, no duplicates). Only paths, names and geometry are stored, never variable values or secrets. On start the last file and its environment are reopened; a file that no longer exists, or an environment name that vanished, is skipped without an error, and a vanished recent file is removed from the list when clicked. A saved window position is used only if it is visible on a current screen (with no screen information it is dropped and the platform places the window), sizes are clamped to the screen; a maximized or fullscreen window is stored as the maximized flag (restored maximized) and keeps the last normal size, and the bogus (-32000, -32000) position Windows reports for a minimized window is ignored. Sizes and positions are device-independent pixels compared with AWT screen bounds: exact on single-DPI setups, not verified on mixed-DPI multi-monitor setups. Entries for files that are missing at start (deleted, or on an absent drive) are hidden from the **Recent files** list but stay stored (the list is still capped at 10), and the remembered environments are capped at the 50 most recently used files. Changes are saved in the background (debounced), saves are serialised so the newest state is always written last, and closing the window flushes them (waiting at most 2 s for a save in flight); a quit that bypasses the window close (macOS Cmd+Q, OS shutdown) can lose changes from the last 500 ms. The file is written atomically and owner-only on POSIX (a config folder created by the app is owner-only too); unknown fields are ignored. A corrupt, empty, foreign or newer-version file is ignored (defaults used, a warning shown) and is not overwritten silently: the next save first copies it to `settings.json.corrupt` (then `.corrupt-1`, `.corrupt-2`, ...; an existing backup is never overwritten and the newest 5 are kept). A file that cannot be read because of an I/O error (locked, permissions, flaky drive) is not treated as corrupt: it is never backed up or replaced, saving is skipped (and reported once) until it can be read again, and then the stored and in-memory settings are merged. A config folder that cannot be written is reported once on stderr and the app keeps working. Two app instances do not merge: the last one to save wins. A file given on the command line wins over the remembered one.

## IntelliJ plugin

Works in any IntelliJ-based IDE, including Community (it depends only on the platform module).

- Open a `.http` file, put the caret in a request and press **Ctrl+Alt+Enter** (or use *Run HTTP Request* in the editor context menu).
- *Select HTTP Environment* in the context menu picks the environment from `http-client.env.json`.
- Results appear in the **HTTP Response** tool window. Chained prerequisites run first, as in the CLI.

- The environment choice is explicit and starts as "(none)" (variables from the file only); pick `dev` etc. in the *Select HTTP Environment* popup or the picker in the tool window. A name that no longer exists is an error, never another environment. The choice is kept per project until the IDE restarts.
- `.http` files get syntax highlighting (methods, URLs, header names, `{{variables}}`, comments, `###` separators, `< file` bodies).
- A green run icon in the gutter on each request line runs that request.
- JSON response bodies are pretty-printed.
- Remembered responses (so a chained request such as a login is not re-sent) are kept per file, environment and loaded variables. Any edit to a file changes its text and drops its session, so the chained requests run again on the next run; the same happens when the file is deleted or moved. At most 16 files are remembered (least recently used are dropped). The tool window's **Reset chain** button forgets all of them.
- Only one request runs at a time: starting another while one runs shows a balloon and does nothing. Saving happens first (also from the gutter icon).
- Failures (including parse and environment errors) are shown in the tool window.
Do not install it next to the bundled HTTP Client plugin of IntelliJ Ultimate, since both claim `.http` files.

## Not yet implemented

Response handlers (`> {% ... %}`, `> file`), opening `<>`-linked responses in the desktop app and plugin, assertion results in the desktop app and plugin (assertions are evaluated and shown by the CLI and reports only), multipart/form bodies and cancel buttons in the UIs.
