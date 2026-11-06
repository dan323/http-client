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
- **Select HTTP Environment** (editor context menu, or the picker in the tool window) picks variables from the nearest
  `http-client.env.json`, searching upward from the file. The default is "(none)" (variables from the file only); a name that
  no longer exists is an error. The choice is kept per project until the IDE restarts.
- Responses of earlier requests (a login) are remembered per file and environment; any edit to the file, or deleting or moving it,
  drops them, and **Reset chain** in the tool window forgets them all. One request runs at a time.
- **HTTP Response** tool window with status, timing, headers and the body (JSON pretty-printed).

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
- `ClientState.kt` — IDE-independent state: environment choice, last report and listeners, the run guard, text-based session invalidation, on top of the shared `RunController`.
- `HttpClientService.kt` — project service wrapping `ClientState`; forgets files on VFS delete/move and closes the `SessionFactory` when the project is disposed.
- `EnvironmentItems.kt` — the picker entries for the environment choice.
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

Not verified (the sandbox IDE exited by itself after about 16 seconds in the agent environment, with no
visible window or interaction):

- Highlighting colors, gutter run icons, the "HTTP Response" tool window and the run/environment actions.
- Behavior in a real Community edition. The sandbox is Ultimate, which also bundles an HTTP Client plugin that
  claims `*.http`; test with that plugin disabled and in an actual Community install.
- Whether the IDE's serialization version is binary compatible with what `adapters` was compiled against
  (1.9.0) when a request actually runs.

The VFS listener, gutter icon and tool window are not covered by tests (they need a running IDE). No completion or navigation for `{{variables}}` yet. Assertion results are not shown (the CLI and reports show them).

`{{$env.NAME}}` reads nothing by default: allow names with `-Dhttpclient.allowEnv=NAME,NAME` (JVM option) or the environment variable `HTTPC_ALLOW_ENV=NAME,NAME`; see [docs/features.md](../docs/features.md).

`< path` / `<@ path` bodies can only be read from the `.http` file's folder. To allow more directories, set the environment variable `HTTPC_ALLOW_FILE_ROOTS=/abs/dir` before starting the IDE (the practical way in an IDE; the alternative is the VM option `-Dhttpclient.allowFileRoots=/abs/dir`). Roots must be absolute (a leading `~` is expanded); a bad value is shown as a configuration error in the tool window. See [docs/features.md](../docs/features.md).
