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
- A bare URL (no method) means `GET`. A trailing `HTTP/1.1` is ignored.
- Headers follow the request line; a blank line starts the body.
- A body consisting only of `< ./path` is read from that file, relative to the `.http` file.

## Variables

`{{name}}` is replaced in the URL, header values, body text and file-body paths.
An undefined variable is an error, so nothing is sent with a placeholder left in.

### Environments

Put environments next to the `.http` file and pick one with `--env`:

```json
// http-client.env.json
{ "dev": { "base": "http://localhost:8080", "user": "alice" } }
```

`http-client.private.env.json` uses the same shape for secrets; its values override the shared file.

### File variables

`@name = value` lines define variables inside the `.http` file. They apply to the requests from that line to the end
of the file; defining the same name again replaces the value from there on. File variables override environment
values, and a value may use `{{...}}` (environment variables, earlier file variables, dynamic variables, response
references).

```
@host = http://localhost:8080
@user = {{$uuid}}

GET {{host}}/users/{{user}}
```

### Request settings

`# @setting [value]` (or `// @setting`) lines before a request line attach settings to it: for example
`# @timeout 5`, `# @no-redirect`, `# @no-cookie-jar`. The parser records them on the request
(`RequestTemplate.settings`); the following are honoured per request by the executor (carried as `HttpRequest.options`):

- `# @timeout 5`: maximum time to wait for the response (the JDK `HttpRequest.timeout`: it bounds waiting for the response headers; it is not an overall deadline and does not cap time spent streaming the body). A whole, positive number: plain numbers are seconds, or add `ms`, `s` or `m` (any case, `@timeout 500ms`). Decimals (`1.5s`), `0`, negatives and values that overflow are rejected. May use `{{vars}}`.
- `# @no-redirect`: do not follow HTTP redirects (the 3xx response is returned). An optional value `true` (default) or `false` (`# @no-redirect false` keeps redirects); it may use `{{vars}}`; any other value is an error.
- `# @proxy host:port`: send the request through that HTTP proxy. Forms: `host`, `host:port`, `http://host:port`, IPv6 as `[::1]:8080`; the port defaults to 80. Only plain HTTP proxies are supported: other schemes (`https://`, `socks://`) and credentials (`user:pass@host`) are rejected. May use `{{vars}}`.

Setting names are case-insensitive and unknown ones are ignored. Invalid values (including a blank `@proxy`) fail when the request is resolved with an `Invalid @timeout` / `@proxy` / `@no-redirect` error, before anything is sent. `JdkHttpExecutor` reuses one derived client per (redirect policy, proxy) and copies the injected client's connect timeout, SSL, authenticator, cookie handler, executor and HTTP version; request priority cannot be copied.

- `# @no-cookie-jar`: do not use the cookie jar for this request (see below). Same optional `true`/`false` value as `@no-redirect`.

### Cookie jar

Each `RequestSession` (one CLI run, one desktop/plugin session) owns an in-memory `CookieJar` in `core`. `Set-Cookie` headers
of a response are stored, and later requests in the same session to a matching host and path get a `Cookie` header, so a login
request followed by an authenticated one just works. Sessions never share cookies and nothing is persisted to disk.
Supported: `Domain` (host-only when absent; a host cannot set cookies for an unrelated domain), `Path`, `Max-Age`, `Expires`,
`Secure` (sent only over https); `Max-Age=0` or a past `Expires` deletes. `# @no-cookie-jar` makes a request neither send nor store cookies.
Limitations: a `Cookie` header written in the request is sent as is (the jar adds nothing); cookies set by intermediate redirect
responses are not captured (the executor follows redirects itself); no public-suffix list; no SameSite handling.

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
- `RequestSession.runAll` evaluates them and attaches one `AssertionResult` (passed, expected, actual, message) each to
  `RunOutcome.Success.assertions`. A request that throws reports `RunOutcome.Failure.assertionsNotRun`.
  `runChained` (single-request runs) evaluates only the requested request; its prerequisites run for their responses
  and their assertions are neither evaluated nor reported. Reused dependencies are not re-asserted.

### Dynamic variables

Each occurrence is evaluated separately. A variable you define with the same name wins.

| Variable            | Value                      |
|---------------------|----------------------------|
| `{{$uuid}}`         | random UUID                |
| `{{$timestamp}}`    | Unix time in seconds       |
| `{{$isoTimestamp}}` | current time, ISO-8601 UTC |
| `{{$randomInt}}`    | integer from 0 to 999      |

## Request chaining

Requests run in file order. Once a **named** request has run, later requests can use its response:

| Reference                         | Value                                |
|-----------------------------------|--------------------------------------|
| `{{login.response.body.$.token}}` | value at a JSONPath in the JSON body |
| `{{login.response.body.*}}`       | whole body                           |
| `{{login.response.headers.X-Id}}` | header value (case-insensitive)      |
| `{{login.response.status}}`       | status code                          |
| `{{page.response.body.//a/@href}}` | XPath over an XML body (also `body.xpath:count(//a)`) |
| `{{page.response.body.regex:id=(\d+)}}` | regex over the body: group 1 if present, else the whole match |

The same expressions (via `ResponseExtractor` in `core`) are what assertions will use.

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
