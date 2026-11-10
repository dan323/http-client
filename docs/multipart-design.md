# T8: multipart bodies (design)

Status: implemented (T8).

## Today

A multipart body is plain text. `Body` is `Text` or `FromFile`; `RequestRunner.build` substitutes variables in the text and calls `toByteArray()`. Consequences:

- Lines are joined with `\n`; multipart needs `\r\n`.
- A file part (`< ./a.png` inside a part) is sent as that literal text, never read.
- The variable analysis (`Dependencies.kt`) does not see variables or file paths inside parts as structure.

## Syntax (the JetBrains form, no new syntax)

```
POST {{host}}/upload
Content-Type: multipart/form-data; boundary=WebAppBoundary

--WebAppBoundary
Content-Disposition: form-data; name="title"

hello {{user}}
--WebAppBoundary
Content-Disposition: form-data; name="data"; filename="a.json"
Content-Type: application/json

< ./a.json
--WebAppBoundary--
```

## Model

```kotlin
sealed interface Body {
    ...
    data class Multipart(val boundary: String, val parts: List<Part>) : Body
}
data class Part(val headers: List<Header>, val content: Body) // Text or FromFile only
```

Built by the parser, not at send time, so that `VariableAnalysis` sees part text and file paths, and errors carry line numbers.

## Parser

- Applies when a `Content-Type` header starts with `multipart/` (case-insensitive) and has a `boundary=` parameter (quoted or not). Otherwise the body stays `Text`. Multipart type without a boundary: warning, body stays `Text`.
- The boundary must be literal text (no `{{var}}`): the parser needs it to split. A variable in it is a parse error.
- Delimiter lines are `--boundary` and the closing `--boundary--`. A line that starts with the boundary text but has other content after it is part content.
- A part is: header lines, a blank line, then content. Content is `Text` (lines joined) or, if it is exactly one `< path` line, `FromFile`.
- Only `< path` is a file part. A `<@ path` line in a part is plain text, as it is for any line that is not exactly `< path`; the parser adds a warning that `<@` file parts are not supported. (JetBrains' `<@` substitutes variables inside the file; this client does not, for whole bodies or parts. See "Out of scope".)
- A body whose first non-blank line is not the opening delimiter is not multipart text (for example `{{rawBody}}`): warning, body stays `Text`.
- A file part may be followed by blank lines.
- Errors (with the line): text after the closing delimiter, a part with no closing delimiter, a part without a `Content-Disposition` header.
- The trailing `>>` / `<>` handling is unchanged and runs first.

## Resolution (`RequestRunner.build`)

- New `MultipartEncoder` in `core/application`, pure: it receives resolved part bytes. Output is `--b\r\n` + headers + `\r\n\r\n` + bytes + `\r\n` ... + `--b--\r\n` in one `ByteArray`, so file parts are binary-safe.
- Text parts: variable substitution as today, also in part header values (`filename="{{name}}"`). Only the structure uses CRLF; line breaks inside text content stay as written.
- File parts: `files.read(text(path))` through the existing confined `FileReader`. No new port, no new I/O in `core`.
- The request `Content-Type` header is sent as written.
- A file part without its own `Content-Type` gets none from us (no guessing from the extension).

## Out of scope

- `<@ path` (variable substitution inside file contents) and `<@utf-8 path`: unsupported for whole bodies today (accepted with a warning, sent as-is) and not added for parts. A separate task could add it for both, for text files only.
- Streaming: the whole body is in memory, as for `< file` today. Note in `known-limitations.md`.
- Highlighting of parts in the lexer and editors: they keep treating the block as body text.
- `application/x-www-form-urlencoded`: already sent as text. How JetBrains joins multi-line urlencoded bodies is unverified, so it stays as is.

## Tests

- Parser: text part, file part, `<@` line in a part (warning, sent as text), several parts, no boundary (warning), `{{var}}` in the boundary (error), unclosed part (error with line), a `--boundary`-lookalike line inside content.
- Encoder: exact bytes including CRLF; binary file content containing `\n` and `\0`.
- Runner: substitution in part text and headers; file part read through a fake `FileReader`; a path outside the allowed roots fails like a `< file` body.
- `VariableAnalysis`: an undefined variable inside a part is reported.
- CLI sample plus `SamplesTest` against the mock server (check first that it can echo multipart).

## Docs

`features.md`, `jetbrains-compatibility.md` (multipart row: Partial to Supported), `known-limitations.md`, `CHANGELOG.md`, `PLAN.md` (T8 done).

## Open questions

1. None. (`mock-server` did not parse multipart; it now lists text parts under `form` and file parts under `files`.)
