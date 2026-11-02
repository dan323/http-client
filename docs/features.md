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
Exit codes: `0` success, `1` a request or setup failed, `2` bad arguments.

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

Response handlers and assertions, and authentication helpers.
