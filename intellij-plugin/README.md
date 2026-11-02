# intellij-plugin

IntelliJ front end for the HTTP client: run JetBrains-style `.http` requests inside the IDE.
It depends only on `com.intellij.modules.platform`, so it is meant to work in Community editions too
(IntelliJ Ultimate ships its own HTTP Client).

## Features

- `.http` file type with syntax highlighting (methods, URLs, header names, `{{variables}}`, comments,
  `###` separators, `< file` bodies).
- Green run icon in the gutter on each request line.
- **Run HTTP Request** (`Ctrl+Alt+Enter`, or the editor context menu) runs the request at the caret,
  plus any requests it chains on (`{{login.response.body.$.token}}`).
- **Select HTTP Environment** (editor context menu) picks variables from `http-client.env.json`
  next to the file. The choice is kept per project until the IDE restarts.
- **HTTP Response** tool window with status, timing, headers and the body (JSON pretty-printed).

See [../docs/features.md](../docs/features.md) for the `.http` syntax, variables and chaining.

## Build

The plugin is compiled against a locally installed IDE. Set `ideaPath` in the root `gradle.properties`
(there are macOS and Windows examples), or pass `-PideaPath=...`.

```
./gradlew :intellij-plugin:buildPlugin    # zip in intellij-plugin/build/distributions/
./gradlew :intellij-plugin:runIde         # sandbox IDE with the plugin
./gradlew :intellij-plugin:test           # lexer tests
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk…*.

## Layout

- `HttpLanguage.kt`, `HttpLexer.kt`, `HttpParserDefinition.kt`, `HttpSyntaxHighlighter.kt` — language support.
  The PSI is flat (a sequence of tokens); request structure comes from `core`'s `HttpFileParser`.
- `RunLineMarker.kt` — gutter run icons.
- `Actions.kt` — run and environment actions.
- `HttpClientService.kt` — project service that runs requests and publishes the output.
- `ResponseToolWindowFactory.kt` — the response panel.
- `src/main/resources/META-INF/plugin.xml` — registrations.

The language id is `HttpClientFile` and the file type is "HTTP Client File", so they do not clash with
Ultimate's `HTTP` language, but both plugins still claim `*.http`.

## Status

Compiles, passes `verifyPluginStructure` and the lexer tests. It has **not** been run in an IDE yet:
the gutter icons, highlighting and tool window are untested by hand, and the bundled
kotlinx-serialization could conflict with the IDE's own copy. No completion or navigation for
`{{variables}}` yet.
