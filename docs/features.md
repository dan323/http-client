# Features

A Postman-style HTTP client that implements a practical subset of the JetBrains `.http` format, with additional testing and execution features.
For where this project differs from the JetBrains HTTP Client, see [jetbrains-compatibility.md](jetbrains-compatibility.md).
Front ends: the `httpc` command line, a desktop app and an IntelliJ plugin, all built on the same `core` and `adapters`.
Internals (mechanisms, class names, security reasoning) are in [design-notes.md](design-notes.md), which has the same section names.

## Contents

- [`.http` files](#http-files)
- [Variables](#variables)
  - [Environments](#environments)
  - [File variables](#file-variables)
  - [Editor diagnostics (analysis)](#editor-diagnostics-analysis)
  - [Request settings](#request-settings)
  - [Cookie jar](#cookie-jar)
  - [Assertions](#assertions)
  - [Run reports](#run-reports)
  - [Saved responses](#saved-responses)
  - [Run history store](#run-history-store)
  - [Authorization shortcuts](#authorization-shortcuts)
  - [Dynamic variables](#dynamic-variables)
- [Request chaining](#request-chaining)
- [Command line](#command-line)
- [Desktop app](#desktop-app)
- [IntelliJ plugin](#intellij-plugin)
- [Not yet implemented](#not-yet-implemented)

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

- `# @name x` or `// @name x` names a request (needed for chaining and `--request`). Lines starting with `#` or `//` are comments.
- A bare URL (no method) means `GET`. A trailing `HTTP/1.1` is ignored. Indented `?a=1` / `&b=2` lines after the request line extend the URL, and an indented header line without `:` continues the previous header value.
- Parse errors name the line: an unknown method (`GTE http://x`, with a "did you mean" hint for a one-letter typo), a method with no URL, a header line without `:`, and the unsupported request types `GRAPHQL`, `WEBSOCKET` and `GRPC` (the file does not run, so nothing is sent as plain HTTP).
- Headers follow the request line; a blank line starts the body.
- A body consisting only of `< ./path` (note the space; `<html>` is plain text) is read from that file, relative to the `.http` file. `<@ path` does the same but does not substitute variables inside the file (warning).
- `> handler`, `>> file` and `<> file` lines count only when they come after the body; earlier ones are body text.
- Response handlers (`> {% ... %}`, `> file`) are not supported: they are not sent as body, and a warning is reported per ignored line (the CLI prints it on stderr). `>> file`, `>>! file` and `<> file` are supported, see [Saved responses](#saved-responses).

**File confinement (deviates from JetBrains, which does not confine).** `< path` and `<@ path` may only read files inside the `.http` file's folder; `..`, absolute paths elsewhere and symlinks pointing out are rejected. A rejected read fails that request with an error naming the path and how to allow it; no file contents are included. To allow more directories:

- CLI: `--allow-file-root DIR` (repeatable).
- Desktop app and IDE plugin: `-Dhttpclient.allowFileRoots=DIR` or the environment variable `HTTPC_ALLOW_FILE_ROOTS=DIR`, several separated by the platform path separator (`:`, or `;` on Windows). In the IDE the environment variable is the practical choice (the property needs VM options).
- Roots must be absolute (a leading `~` is expanded). Relative or empty roots, unparsable paths and filesystem roots such as `/` or `C:\` are rejected with an error naming the entry; a root equal to your home directory only prints a warning.

Confinement is a guard against files you download, not against a local attacker: someone with write access inside an allowed folder could still race the check by swapping a parent folder for a symlink ([design notes](design-notes.md#http-files)).

## Variables

`{{name}}` is replaced in the URL, header values, body text and file-body paths. An undefined variable is an error, so nothing is sent with a placeholder left in.

### Environments

Put `http-client.env.json` in the `.http` file's folder or any parent folder and pick one with `--env`. All front ends use the same rule: the nearest enclosing folder containing `http-client.env.json` wins; the `http-client.private.env.json` beside it is merged (a folder holding only the private file counts). The search starts in the `.http` file's folder, goes up through parent folders, stops after the first folder containing a `.git` entry, and never enters the home directory (or the system temp directory) or anything above it, so a stray `~/http-client.env.json` cannot leak into unrelated projects.

```json
// http-client.env.json
{ "dev": { "base": "http://localhost:8080", "user": "alice" } }
```

`http-client.private.env.json` uses the same shape for secrets; its values override the shared file.

The choice is explicit: no environment (variables from the file only; the CLI default) or a named one. A name that does not exist is an error, never another environment. A JSON `null` value is treated as undefined; a malformed env file fails with an error naming the file and position. Running a request reads the env files only when a named environment is chosen; the desktop app also reads them when opening or saving a request and the plugin to fill its picker, so a malformed file can be reported even with no environment chosen.

### File variables

`@name = value` lines define variables inside the `.http` file. They apply from that line to the end of the file; defining a name again replaces the value from there on. File variables override environment values, and a value may use `{{...}}` (environment variables, earlier file variables, dynamic variables, response references).

```
@host = http://localhost:8080
@user = {{$uuid}}

GET {{host}}/users/{{user}}
```

- Only values written in the file are expanded: an environment or response value that contains `{{` is sent as is, so environment values cannot reference other variables. Compose them with file variables instead.
- A variable that refers to itself reads the environment's value of that name (`@host = {{host}}/api`) and fails with `Undefined variable: host` when there is none. A cycle (`@a = {{b}}`, `@b = {{a}}`) fails with `Circular variable reference: a -> b -> a`. A value needing more than 10,000 distinct expansions (or 100 nested levels) fails instead of hanging.
- Running a single request (`--request`, "run this request") also runs the requests it reads responses from, including through file variables, `# @assert` expressions and settings. Request names may contain dots (`auth.login`).

### Editor diagnostics (analysis)

The `{{...}}` of a `.http` text can be checked without running anything, for the editors (desktop and plugin use it in later tasks). Messages name variables and requests, never values.

| Code | Severity | Meaning |
|---|---|---|
| `undefined-variable` | error | not a file variable, environment variable or known dynamic variable |
| `unknown-request` | error | `{{x.response...}}` and no request is named `x` |
| `request-not-run-yet` | warning | the request `x` first appears later in the file (works at run time only if it already ran in the same session) |
| `self-reference` | warning | a request reads its own response (works on a later run in the same session, e.g. a polling request) |
| `malformed-reference` | error | `x.response` with nothing after it, an unknown part (not `status`, `headers.Name`, `body...`) or an empty `headers.`/`body.` |
| `invalid-dynamic-variable` | error | bad arguments, e.g. `$random.integer(5, 1)` |
| `circular-variable` | error | file variables that refer to each other |
| `unterminated-reference` | warning | `{{` with no `}}` after it |
| `invalid-reference-text` | warning | `{{ a b }}`: not a reference, sent as written |
| `duplicate-request-name` | warning | the later `# @name` repeats an earlier one |
| `variable-defined-later` | warning | an `@a = {{b}}` value uses a file variable defined below it |
| `parse-error` | error | the parser rejected a line (its message, on that line); one per `###` block |

A file that does not parse is still analysed: each rejected line is a `parse-error` and left out, so the other diagnostics keep working while a request is half typed. The rules are the run-time ones. Scanned: request and query lines, header values, body text, the path of a `< path` body or `>> path` / `>>! path` save, file variable values, `# @assert` expressions and the values of `@timeout`, `@no-redirect`, `@no-cookie-jar`, `@proxy`. Not scanned: comments, `# @name`, other settings. Never flagged, because they depend on run time: whether `body.$.x` finds anything, and `$env.X` / `$processEnv X`.

### Request settings

`# @setting [value]` (or `// @setting`) lines before a request line attach settings to it. Names are case-insensitive and unknown ones are ignored. An invalid value (including a blank `@proxy`) fails the request with an `Invalid @timeout` / `@proxy` / `@no-redirect` error before anything is sent. All values may use `{{vars}}`.

- `# @timeout 5`: maximum time for the whole exchange, headers and body; on expiry the request fails with a timeout. A whole positive number: plain numbers are seconds, or add `ms`, `s` or `m` (`@timeout 500ms`); decimals (`1.5s`), `0`, negatives and overflowing values are rejected. Without it the default of 60 s applies, or the CLI `--timeout`, or the system property `httpclient.timeout` / environment variable `HTTPC_TIMEOUT` (same format; read by the desktop app and the plugin; a bad value is reported when a request runs, never replaced by the default). The connect timeout is 30 s. The limit cannot be disabled: use a long `@timeout` for slow endpoints.
- `# @no-redirect`: do not follow redirects (the 3xx response is returned). Optional value `true` (default) or `false` (keeps redirects); anything else is an error.
- `# @proxy host:port`: send the request through that HTTP proxy. Forms: `host`, `host:port`, `http://host:port`, IPv6 as `[::1]:8080`; the port defaults to 80. Other schemes (`https://`, `socks://`) and credentials (`user:pass@host`) are rejected.
- `# @no-cookie-jar`: neither send nor store cookies for this request (see below). Same optional `true`/`false` value as `@no-redirect`.

The JDK client controls `Host`, `Content-Length`, `Connection`, `Expect` and `Upgrade` itself, so user-set headers with these names are dropped silently.

### Cookie jar

Each session owns an in-memory cookie jar: one CLI run; in the desktop app and plugin one per file, environment and loaded variables. `Set-Cookie` headers of a response are stored, and later requests in the same session to a matching host and path get a `Cookie` header, so a login followed by an authenticated request just works. In the desktop app and plugin the session is kept, so when you send one request the named requests it chains on that already ran (a login) are not sent again until the environment file changes or the chain is reset; the selected request itself, and every request of Send all, always run again. Sessions never share cookies and nothing is persisted to disk.

Supported attributes: `Domain` (host-only when absent; a host cannot set cookies for an unrelated domain, and a `Domain` that is a public suffix per the Public Suffix List, such as `com`, `co.uk` or `github.io`, is rejected unless it equals the request host, in which case the cookie stays host-only), `Path`, `Max-Age` (wins over `Expires`), `Expires` (lenient date parsing; unparseable is ignored), `Secure`. `Max-Age<=0` or a past `Expires` deletes the cookie.

Redirects: the session follows them itself, one hop at a time, up to 10 hops, so `Set-Cookie` on a `302` (the `POST /login` -> `302` flow) is stored and the `Cookie` header is recomputed for every hop. 301/302 on `POST` and 303 switch to `GET` without body; 307/308 keep method and body (re-sent to the new origin, as the JDK and curl do). `@timeout` is one deadline across all hops and the reported duration is the sum. With `@no-redirect` the first response is returned (its cookies are still stored). With `@no-cookie-jar` redirects are followed the same way, only without reading or storing cookies.

**Redirect credentials:** an explicit `Cookie`, `Authorization` or `Proxy-Authorization` header is dropped when a redirect leaves the origin (scheme, host, port) and kept on same-origin hops. Only these three are stripped: other custom headers such as `X-API-Key` still cross origins, so do not put a secret in one if the server may redirect elsewhere.

Limitations: a `Cookie` header written in the request is sent as is (the jar adds nothing); an IP host only matches exactly; `Secure` cookies are neither accepted from nor sent over plain http (no localhost exception), and a plain-http response cannot overwrite a `Secure` cookie; a cookie over 4096 bytes is ignored, at most 50 per domain and 3000 in total (oldest first evicted); no SameSite handling. The Public Suffix List is a bundled snapshot; how to refresh it is in the [design notes](design-notes.md#cookie-jar).

### Assertions

`# @assert <expression> <operator> [expected]` lines before a request line check its response. They may repeat.

```
# @assert status == 200
# @assert headers.content-type contains json
# @assert body.$.id exists
# @assert body.$.n > 3
GET {{base}}/items/1
```

- `<expression>` is `status`, `headers.Name` or `body.<...>` (`body.*`, `body.$.json.path`, `body./xpath`, `body.regex:...`). Any other prefix (a typo such as `stauts`) is an invalid assertion. The expression ends at the first space; to use one with spaces, quote it: `# @assert "body.$.items[?(@.id == 1)]" exists`.
- `expected` is the rest of the line, inner spaces kept. In matching single or double quotes it is literal (`== ""` asserts an empty value) and `==`/`!=` compare as exact text. An unquoted empty expected value is invalid.
- Operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `contains`, `!contains`, `startsWith`, `endsWith`, `matches` (the whole value matches a regex; `.` also matches newlines; a pattern running over 2 seconds is aborted and reported as invalid), `exists`, `!exists` (these take no expected value).
- Numbers are strict decimals, compared exactly. `==` holds when the texts are identical or both sides are numbers of the same value: `5 == 5.0`, `1.10 == 1.1` and `1e3 == 1000` pass (quote the expected value to compare text), `007 == 7` fails (leading zeros are text), as do `NaN`/`Infinity`. `<`, `<=`, `>`, `>=` need numbers on both sides.
- A value that cannot be found fails every operator except `!exists`, including `!=` and `!contains`. An XPath that evaluates to a string (`xpath:string(//a)`, `count(...)`) gives `""` rather than missing, so `exists` holds; use `== ""` to test for emptiness.
- `{{variables}}` and `{{name.response...}}` are substituted before evaluating, as in request text. An undefined one makes the assertion fail as invalid ("Undefined variable: x"). Resolved values are never shown: a failing comparison reports "expected value contains substitutions, not shown".
- A malformed assertion (including a bare `# @assert`) fails with an "invalid assertion" message. A trailing ` # comment` is stripped (only a `#` preceded by whitespace and outside quotes, so `== "x #y"` is safe). `# @assert` lines must sit before the request line. Failure messages show at most 200 characters of the actual value.
- With `--request`, only the requested request's assertions are evaluated; its prerequisites run for their responses and are not asserted.

### Run reports

A report has one entry per request (name, method, URL as written in the file, status, duration, assertion results, error, optional `httpError`) plus totals and an overall pass/fail. A request passes when it completed and all its assertions passed; with `--fail-on-http-error` it also needs a status below 400. By default a 4xx/5xx response without a failed assertion counts as passed, so a green report does not mean "no 5xx".

The CLI writes reports with `--report-json FILE` and `--report-junit FILE` (console output is unchanged). Option values must be non-empty and not start with `--`. Usage errors (exit 2, nothing is run or written): repeating an option, both reports on the same path, a report path that is the input `.http` file (also through `..` or a symlink) and combining a report option with `--list`. Parent directories are created and files are written atomically; new report files are owner-only (POSIX) because they may contain secrets. If the run cannot start (missing file, unknown environment or request) a report with a single failed `httpc` entry is still written so CI finds a file. Exit codes are listed under [Command line](#command-line).

- JUnit XML: one `testcase` per request, named `"<index>. <request name or METHOD url>"` with the file name as `classname`. Failed assertions of a request are merged into one `failure` of type `AssertionFailure`; with `--fail-on-http-error` a 4xx/5xx adds a separate `failure` of type `HttpStatusFailure`; a request that could not complete is an `error` (time 0). `tests`, `failures` and `errors` count testcases, so a testcase with two `failure` elements counts once; an empty run reports `tests="0"`, which some CI systems treat as a failure or a missing report. Prerequisites (requests run only to feed chaining) are omitted when they passed and kept as an `error` when they failed.
- JSON: top-level `version` (currently 1); `passed` and `totals` are derived values written for consumers; prerequisites are always listed with `"prerequisite": true`.
- Reports contain URLs as written in the file and error messages, which may include secrets (tokens in query strings): treat them as sensitive CI artifacts.

### Saved responses

After the request (and after a `> {% ... %}` handler, which is parsed but ignored), as in the JetBrains plugin:

```
GET {{host}}/orders/1

>> out/order-{{id}}.json
<> order.json
```

- `>> path` saves the response body to `path` and never overwrites: an existing name gets a numeric suffix before the extension (`out.json`, `out-1.json`, `out-2.json`). `>>! path` overwrites. `<> path` links the request to its latest stored response (see below).
- The `>>`/`<>` lines are accepted in any order but only after the body. A `>>`, `>>!` or `<>` line is a directive only when every non-blank line after it is also a directive or a response handler; `>>x` without a space never is. As in JetBrains, a last body line starting with `>> ` (`hello` then `>> quoted reply`) is read as a directive; the parser warns when such a line directly follows the body and its path contains whitespace. A blank line before the directive removes the doubt; to keep it as body text, put more body text after it.
- At most one `>>`/`>>!` per request (a second is a parse error with its line number); several `<>` lines are fine. An empty path is a parse error.
- The path may contain `{{variables}}`, is relative to the `.http` file, and is validated exactly as written (leading or trailing whitespace is refused). `\` counts as a separator on every platform. Names that cannot exist on Windows are refused on every platform: reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9`, also with an extension), a segment ending in a dot or a space, and the characters `< > : " | ? *` and control characters (which rules out `file.txt:stream`).
- Confinement is the one of `< path` bodies (the `.http` file's folder plus the allowlist): `..` out of the allowed folders, absolute paths, symlinks leading out, a final symlink, a folder, the `.http` file itself and (for `>>!`) a read-only file are refused. Missing parent folders are created inside the allowed roots. Files are written atomically, owner-only on POSIX (an overwritten file keeps its permissions), byte for byte (no charset conversion). Residual risk: someone with write access inside an allowed folder who can win a race may make one save land outside it; that is detected afterwards, a `>>` file is removed and a `>>!` replacement cannot be restored (the error says so; [design notes](design-notes.md#saved-responses)).
- The save happens after the assertions, also when they failed, but not when the request failed. A rejected or failed save fails only the save: the request's result stays and the error names the path, hides substituted values (shown as `{{name}}`, 3 characters or more) and never contains the body. The same rule applies to the path printed after a successful save (the CLI `Saved response:` line, `Saved to` in the apps): `>> out/{{token}}.json` prints `out/{{token}}.json`, while the file on disk has the real name. Paths are not part of reports.
- A `>> {{other.response...}}` path makes `other` a prerequisite that runs first. A prerequisite is sent like any other request, so its own `>>` and `<>` directives fire; one that is reused instead of re-sent does not save again.
- CLI: `Saved response: PATH` (relative to the `.http` file) on stderr, also with `--quiet`; a failed save prints `Error: Cannot save response to ...` and makes the exit code 1. A `--report-*` / `--output` file that is the same file as a saved response is not written (an error is printed and the saved response is kept).
- Desktop app and plugin show `Saved to PATH` (or the save/link error) under the status of the response; the desktop app records `<>` links like the CLI and the plugin.

`<> path` links: JetBrains rewrites the `.http` file to add a `<>` line per stored response. This tool never edits your `.http` file; instead, after a run of a request with a `<>` line, its latest response is recorded in `.http-history/`. A request is identified by its `# @name` (duplicate names share one link, the newest run wins) or, unnamed, by the line where it starts (adding lines above it breaks the link). The newest record of every `<>` request is kept however often other requests run; an entry whose request lost its `<>` line or was removed stays until you delete it from `.http-history/`. Corrupt-history warnings go to stderr. Opening the linked response in the desktop app or the plugin is not implemented yet.

### Run history store

Responses recorded for `<>` links go to `.http-history/<file name>/` next to the `.http` file; ordinary runs are not recorded yet. The newest 50 runs per file are kept, and bodies over 5 MB are cut and marked as truncated. Corrupt or newer entries are skipped with a warning, never fatal.

Stored responses are raw (tokens, cookies, personal data). Folders and files are owner-only on POSIX; on Windows the default folder permissions apply. Add `.http-history/` to your `.gitignore` (this repository does) and never share the folder. Layout and pruning: [design notes](design-notes.md#run-history-store).

### Authorization shortcuts

As in the JetBrains HTTP Client, `Authorization: Basic` accepts plain credentials, which are Base64-encoded (UTF-8) when the request is sent:

```
Authorization: Basic {{user}} {{pass}}
Authorization: Basic {{user}}:{{pass}}
```

Variables resolve first. After `Basic`, a value that contains `:` or has two or more whitespace-separated tokens is plain credentials (user is the text before the first `:`, or the first token; everything after is the password, so passwords may contain `:` and spaces; an empty password `user:` is fine). A single token without `:` is taken as already encoded and sent unchanged, as is every other scheme (`Bearer {{token}}` is just a header). The header name and `Basic` are case-insensitive. A username without a password written as `Basic user` is read as an already-encoded token; write `Basic user:`. The encoded value, the `user:password` text and the password are kept out of failure messages. Not implemented: `Digest` credentials and `{{$auth.token(...)}}` / OAuth helpers.

### Dynamic variables

Each occurrence is evaluated separately. A variable you define with the same name wins.

| Variable            | Value                      |
|---------------------|----------------------------|
| `{{$uuid}}`         | random UUID                |
| `{{$timestamp}}`    | Unix time in seconds       |
| `{{$isoTimestamp}}` | current time, ISO-8601 UTC |
| `{{$randomInt}}`    | integer from 0 to 999      |

More (names modelled on the JetBrains HTTP Client and Postman):

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

- **Offsets** are `<amount> <unit>` after `$timestamp` / `$isoTimestamp`: a whole number with an optional sign (`1`, `-2`, `+5`) and one of `ms`, `s`, `m` (minutes), `h`, `d`, `w`, `M` (calendar months), `y` (calendar years); the space is optional (`90s`). Units are case-sensitive. Month and year offsets are calendar arithmetic in UTC and clamp to the end of a shorter month. The syntax is our own, loosely based on JetBrains' offsets; `$datetime` / `$localDatetime` are not supported.
- Arguments in `$random.*(...)` are comma separated. Wrong argument counts, non-numbers, `from` greater than `to` (for `$random.float` also equal), an unknown offset unit or a length outside 0..10000 fail with `Invalid dynamic variable {{...}}: <reason>` (in an `# @assert` line only that assertion is invalid). An unknown `$name` fails as `Undefined variable: <name>`. `$random.float` prints as plain decimal, never `1.0E10`. Only `$` followed by a letter starts a dynamic variable.
- **`$env` is opt-in.** A `.http` file you download could otherwise send `AWS_SECRET_ACCESS_KEY` or any token in your environment to a server of its choosing, so no variable is readable until you allow its name (exact match; anything else fails with `Invalid dynamic variable {{$env.X}}: environment variable 'X' is not allowed; <how to allow it>`). An allowed name that is not set fails as `Undefined variable: $env.X`. Prefer `http-client.env.json` / `.private.env.json` for secrets. How to allow names:
  - CLI: `--allow-env NAME[,NAME]` (repeatable), e.g. `httpc api.http --allow-env API_TOKEN,HOME`; the property and variable below are honoured too and merged with the flag.
  - Desktop app and IntelliJ plugin (no UI yet): the system property `-Dhttpclient.allowEnv=NAME,NAME` (in the IDE under Help | Edit Custom VM Options) and/or the environment variable `HTTPC_ALLOW_ENV=NAME,NAME` of the process that starts the app/IDE. Restart to apply. All sources are merged. The environment of a GUI-launched IDE may differ from your shell.
- `$env` values are inserted as plain text (not scanned for `{{...}}` again). Reports and CLI output show the request as written (with `{{$env.X}}` unresolved), and error messages name the variable, never its value. The server's response may of course echo what you sent.
- `$uuid`, `$random.uuid` and the `$random.*` family use a cryptographically strong generator (our choice, not taken from JetBrains).
- JetBrains reads process environment with `{{$processEnv NAME}}` as far as we know (unverified); here `{{$env.NAME}}` is the main form and `$processEnv NAME` an alias. JetBrains has no allowlist, so a file using `$processEnv` there will not need `--allow-env` but will here.

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

The same expressions are what assertions use. The body is decoded with the `charset` of the response's `Content-Type` (UTF-8 when absent or unknown). JSONPath supports a subset: `$`, `.key`, `['key']`, `[index]`; objects and arrays are returned as JSON text. XPath runs with secure processing (no DOCTYPE, no external access, no extension functions); XPath and regex stop after 2 seconds and find nothing, and at most 4 XPath evaluations run at a time in the whole process (a fifth fails fast and finds nothing). Referencing a request that has not run, or a path that does not exist, is an error.

Failure messages (also in reports and on stderr) never contain substituted variable values: a value that appears in an error text is shown as `{{name}}`. Values shorter than 3 characters are not replaced.

## Command line

```
httpc <file.http> [--env NAME] [--request NAME|INDEX] [--allow-env NAME[,NAME]] [--allow-file-root DIR] [--list]
                  [--report-json FILE] [--report-junit FILE] [--quiet] [--output FILE] [--var NAME=VALUE] [--timeout DURATION]
                  [--fail-on-http-error]
```

| Option        | Effect                                               |
|---------------|------------------------------------------------------|
| `--env NAME`  | use variables from `NAME` in the nearest `http-client.env.json` (see Environments); without it no environment is used |
| `--request X` | run the request named `X`, or the Nth (1-based), plus the named requests it reads responses from (transitively), in file order |
| `--allow-env N` | let `{{$env.N}}` read these OS environment variables (repeatable) |
| `--allow-file-root DIR` | also let `< path` bodies be read from `DIR` (repeatable) |
| `--list`      | list requests and exit (cannot be combined with `--report-*`, `--quiet`, `--var`, `--timeout`, `--fail-on-http-error`, `--output`) |
| `--report-json FILE` / `--report-junit FILE` | write a run report (see Run reports) |
| `--quiet`     | no per-request output on stdout (see below) |
| `--output FILE` | write the raw response body of the single selected request to `FILE` |
| `--var NAME=VALUE` | set a variable, overriding file and environment variables (repeatable) |
| `--timeout DURATION` | total time limit for requests without `# @timeout`: `30`, `500ms`, `45s`, `2m` (default 60 s) |
| `--fail-on-http-error` | exit 1 when a selected request gets a response with status >= 400 |

For each request the output is the status line with timing, the response headers, then the body. After a response, each assertion is printed as `PASS ...` or `FAIL ... -- message` (`NOT RUN ...` when the request failed), and a total line `Assertions: N passed, M failed, K not run` follows the run. After a run the CLI prints a summary such as `3 requests, 2 passed, 1 failed in 1.2s` to **stderr** (so stdout stays the response data); it counts every request that ran, prerequisites included. It is not printed for `--list` or when the run cannot start.

**`--quiet`:** nothing per request on stdout (no heading, status, headers, body, `PASS`, `NOT RUN` or the `Assertions:` total). Failed assertions go to stderr as `FAIL ...`, request errors as `Error: ...`, and the summary line still appears.

**`--fail-on-http-error`:** by default an HTTP error status is not a failure (a `404` or `503` without a failed assertion exits `0`). With the option, any selected request answering status >= 400 fails the run (exit `1`) even if its assertions pass, printed to stderr as `FAIL HTTP 503 from <request name or METHOD url> (--fail-on-http-error)`, also with `--quiet`. Prerequisites that run only to feed chaining are not judged; redirects (3xx) are not errors. In reports the entry fails: JSON gets `"httpError": "HTTP 503"` and `passed: false`; JUnit gets a `failure` of type `HttpStatusFailure` (message such as `HTTP 503 from GET http://host/path`).

**`--output FILE`:** after the run, the body of the selected request's response is written to `FILE` as the raw bytes received, byte for byte: images, PDFs and archives stay intact, a non-UTF-8 charset keeps its encoding, and a `Content-Encoding: gzip` / `br` response is saved still compressed (the client does not decompress). It needs exactly one selected request (use `--request`, or a file with one request), otherwise it is a usage error (exit `2`, before anything is sent). Prerequisites are never written, nothing is written when that request fails. Parent folders are created, the write is atomic and a new file is owner-only (POSIX) because bodies may contain secrets. `FILE` must differ from the input `.http` file and from the report paths (also through `..`, symlinks or case-only differences) and cannot be repeated.

**`--var NAME=VALUE`:** the name is everything before the first `=` and must be non-empty without whitespace or braces; the value may be empty or contain `=`. Repeating a name is a usage error. Precedence: `--var` beats file variables (`@name = value`), which beat the environment file; other file variables that mention an overridden name see the `--var` value. Values are replaced by `{{name}}` in failure messages and usage errors never echo them.

**Exit codes**, by precedence: `2` bad arguments (usage is printed, nothing runs); `1` a request or setup failed (missing file, unknown environment or request), an assertion failed, a selected request got an HTTP error status with `--fail-on-http-error`, or any other error; `3` the run passed but a requested report or output file could not be written; `0` otherwise. Parser warnings (ignored response handlers) go to stderr as `warning: FILE: ...`; request failures as `Error: ...`.

Run with `./gradlew :cli:run --args="requests.http --env dev"`, or build a launcher with `./gradlew :cli:installDist` (output in `cli/build/install/httpc/bin`).

## Desktop app

A standalone window for Windows, macOS and Linux: file editor, request list, environment picker, and a response viewer. See [desktop/README.md](../desktop/README.md).

- The environment choice is explicit: "none" (default; variables from the file only) or a name from the nearest `http-client.env.json`. A name that vanished is reported as an error, never replaced.
- One session is kept per file, environment and loaded variables, so **Send** on a chained request does not resend a login that already ran (reused prerequisites are not listed). Editing the text does not drop it; changing the environment file does. **Reset chain** drops all sessions so the next Send reruns the chain. **Send all** and the selected request always run again.
- Only one run at a time: while it runs, editing, saving, opening and creating files are refused. A run first saves the file; a save error stops the run and stays visible.
- **Remembered between runs** (`settings.json` in the user config folder: `$XDG_CONFIG_HOME/http-client` or `~/.config/http-client` on Linux, `~/Library/Application Support/http-client` on macOS, `%APPDATA%\http-client` on Windows): the last opened file, the last environment per file, the window size, position and maximized state, and the 10 most recent files (**Recent files** button). Only paths, names and geometry are stored, never variable values or secrets. On start the last file and its environment are reopened; a file or environment that no longer exists is skipped without an error, and a file given on the command line wins. Recent files that are missing at start are hidden but stay stored (a vanished one is removed when clicked), and environments are remembered only for the 50 most recently used files. A saved window position is used only if it is visible on a current screen. Changes are saved in the background and flushed when the window closes; a quit that bypasses the window close (macOS Cmd+Q, OS shutdown) can lose changes from the last 500 ms. The file is owner-only on POSIX. A corrupt or newer-version file is ignored (defaults used, a warning shown) and the next save first copies it to `settings.json.corrupt`; a file that cannot be read because of an I/O error is neither backed up nor replaced until it can be read again. A config folder that cannot be written is reported once on stderr and the app keeps working. Two app instances do not merge: the last one to save wins. Details: [design notes](design-notes.md#desktop-app).

## IntelliJ plugin

Works in any IntelliJ-based IDE, including Community (it depends only on the platform module).

- Open a `.http` file, click the green gutter icon next to the request you want to run (the icon runs its own request wherever the caret is), or put the caret in a request and use *Run HTTP Request* in the editor context menu. The shortcut is **Ctrl+Alt+R**.
- *Select HTTP Environment* in the context menu picks the environment from `http-client.env.json`.
- Results appear in the **HTTP Response** tool window. Chained prerequisites run first, as in the CLI.

- The environment choice is explicit and starts as "(none)" (variables from the file only); pick `dev` etc. in the *Select HTTP Environment* popup or the environment picker in the header of the `.http` editor (as in JetBrains' HTTP Client; it applies to runs from every file). A name that no longer exists is an error, never another environment. The choice is kept per project until the IDE restarts.
- `.http` files get syntax highlighting (methods, URLs, header names, `{{variables}}`, comments, `###` separators, `< file` bodies).
- A green run icon in the gutter on each request line runs that request.
- JSON response bodies are pretty-printed.
- Remembered responses (so a chained request such as a login is not re-sent) are kept per file, environment and loaded variables. Any edit to a file changes its text and drops its session, so the chained requests run again on the next run; the same happens when the file is deleted or moved. At most 16 files are remembered (least recently used are dropped). The tool window's **Reset chain** button forgets all of them.
- Only one request runs at a time: starting another while one runs shows a balloon and does nothing. Saving happens first (also from the gutter icon).
- Failures (including parse and environment errors) are shown in the tool window.
Do not install it next to the bundled HTTP Client plugin of IntelliJ Ultimate, since both claim `.http` files.

## Not yet implemented

Response handlers (`> {% ... %}`, `> file`), opening `<>`-linked responses in the desktop app and plugin, assertion results in the desktop app and plugin (assertions are evaluated and shown by the CLI and reports only), multipart/form bodies and cancel buttons in the UIs.
