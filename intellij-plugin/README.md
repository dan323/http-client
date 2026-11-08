# intellij-plugin

IntelliJ front end for the HTTP client: run JetBrains-style `.http` requests inside the IDE.
It depends only on `com.intellij.modules.platform`, so it is meant to work in Community editions too
(IntelliJ Ultimate ships its own HTTP Client).

## Features

- `.http` file type with syntax highlighting (methods, URLs, header names, `{{variables}}`, comments,
  `###` separators, `< file` bodies).
- Green run icon in the gutter on each request line.
- **Run HTTP Request** (editor context menu, or `Ctrl+Alt+R`; see [known limitations](../docs/known-limitations.md)) runs the request at the caret,
  plus any requests it chains on (`{{login.response.body.$.token}}`).
- **Select HTTP Environment** (the picker in the header of every `.http` editor, or the editor context menu) picks variables from the nearest
  `http-client.env.json`, searching upward from the file. The default is "(none)" (variables from the file only); a name that
  no longer exists is an error. The choice is kept per project until the IDE restarts.
- Responses of earlier requests (a login) are remembered per file and environment; any edit to the file, or deleting or moving it,
  drops them, and **Reset chain** in the tool window forgets them all. One request runs at a time.
- **HTTP Response** tool window (with the **Reset chain** and **Cancel** buttons) with status, timing, assertion results (`# @assert`: a summary and a PASS/FAIL line each; unverified by hand in an IDE), headers and the body (JSON pretty-printed).
- **Cancel** (a button in the tool window and the title-bar action *Cancel HTTP Request*, enabled only while a request runs) stops the running request; the tool window shows `Cancelled` above the responses that had arrived, and the cancelled request is not remembered. Unverified by hand in an IDE (only the shared logic and `ClientState` have tests).

See [../docs/features.md](../docs/features.md) for the `.http` syntax, variables and chaining.

## Build

The plugin is compiled against a locally installed IDE. Set `ideaPath` in the root `gradle.properties`
(there are macOS and Windows examples), or pass `-PideaPath=...`.

```
./gradlew :intellij-plugin:buildPlugin    # zip in intellij-plugin/build/distributions/
./gradlew :intellij-plugin:runIde         # sandbox IDE with the plugin
./gradlew :intellij-plugin:test           # lexer, environment-item and ClientState (run guard, session reuse) tests; needs an IDE
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk…*.

## Layout

- `HttpLanguage.kt`, `HttpLexer.kt`, `HttpParserDefinition.kt`, `HttpSyntaxHighlighter.kt` — language support.
  The PSI is flat (a sequence of tokens); request structure comes from `core`'s `HttpFileParser`.
- `RunLineMarker.kt` — gutter run icons.
- `Actions.kt` — run and environment actions.
- `EnvironmentEditorHeader.kt` — the environment picker shown in the header of `.http` editors (an `EditorNotificationProvider`).
- `ClientState.kt` — IDE-independent state: environment choice, last report and listeners, the run guard, text-based session invalidation, on top of the shared `RunController`.
- `HttpClientService.kt` — project service wrapping `ClientState`; forgets files on VFS delete/move and closes the `SessionFactory` when the project is disposed.
- `EnvironmentItems.kt` — the picker entries for the environment choice (used by the editor header and the popup action).
- `ResponseToolWindowFactory.kt` — the response panel; JSON colouring and status/duration formatting come from the shared `view-model` module (its kotlinx-serialization is excluded from the plugin zip, the IDE provides it).
- `src/main/resources/META-INF/plugin.xml` — registrations.

The language id is `HttpClientFile` and the file type is "HTTP Client File", so they do not clash with
Ultimate's `HTTP` language, but both plugins still claim `*.http`.

## Status

Verified (T21, IntelliJ IDEA Ultimate 2026.1.5 as the build target, `since-build` 261, no `until-build`):

- `buildPlugin`, `verifyPluginStructure` and the lexer tests pass; `plugin.xml` is patched correctly.
- `runIde` starts the sandbox IDE, loads "HTTP Client (0.2.0)" and `idea.log` shows no errors or plugin
  problems from this plugin (the only warnings are unrelated Ultimate-only bundled plugins and the JetBrains
  Station socket).
- **kotlinx-serialization conflict fixed:** the IDE ships its own `kotlinx-serialization` jars, so the plugin
  distribution no longer bundles 1.9.0 (they are excluded from `runtimeClasspath` in `build.gradle.kts`; the zip
  now holds only `core`, `adapters` and the plugin jar). Kotlin stdlib is also not bundled.
  `verifyPluginProjectConfiguration` still warns about the stdlib; the suggested
  `kotlin.stdlib.default.dependency=false` is a global property that would break the other modules, so it is not set.

Verified by hand (T21) with the release zip of `0.5.0-rc1` installed from disk in IntelliJ IDEA Ultimate
2026.3 (build `IU-263.7307.200`), with Ultimate's bundled HTTP Client plugin disabled: highlighting (comments,
methods, URLs, variables), the gutter run icon, *Run HTTP Request* in the context menu, the "HTTP Response" tool
window with a pretty-printed JSON body, the environment picker (including "(none)" and the undefined-variable
error), chaining, session reuse and its reset on edit and **Reset chain**, error display, the "request already
running" balloon, and the reset of the environment choice on restart. Requests also run, so the IDE's
`kotlinx-serialization` is compatible with what `adapters` was compiled against.

Known problems: the default shortcut `Ctrl+Alt+R` has not been tried in a running IDE yet
(see [known limitations](../docs/known-limitations.md)).

Not verified: a real Community install, and the 2026.1 baseline (build 261, the `since-build`) itself: the plugin
was run on 2026.3 only. It does not load in 2025.3 (build 253) by design.

The VFS listener, gutter icon and tool window are not covered by tests (they need a running IDE). No completion or navigation for `{{variables}}` yet. The assertion rendering is plain text in the tool window and was not run in an IDE.

`{{$env.NAME}}` reads nothing by default: allow names with `-Dhttpclient.allowEnv=NAME,NAME` (JVM option) or the environment variable `HTTPC_ALLOW_ENV=NAME,NAME`; see [docs/features.md](../docs/features.md).

`< path` / `<@ path` bodies can only be read from the `.http` file's folder. To allow more directories, set the environment variable `HTTPC_ALLOW_FILE_ROOTS=/abs/dir` before starting the IDE (the practical way in an IDE; the alternative is the VM option `-Dhttpclient.allowFileRoots=/abs/dir`). Roots must be absolute (a leading `~` is expanded); a bad value is shown as a configuration error in the tool window. See [docs/features.md](../docs/features.md).
