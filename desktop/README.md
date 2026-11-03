# desktop

Standalone desktop front end for the HTTP client, built with
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for Desktop.
It runs on Windows, macOS and Linux and reuses `core` and `adapters` unchanged.

## What it does

- **Open…** / **New…** a `.http` file, or pass one at launch (`./gradlew :desktop:run --args="samples/basic.http"`).
  The text is editable in the middle pane with syntax highlighting (methods, URLs, header names, `{{variables}}`,
  comments, `###` separators, `< file` bodies; light and dark palettes). **Save** writes it back; running a request saves first.
- Shortcuts: **Ctrl/Cmd+S** saves, **Ctrl/Cmd+Enter** sends the selected request.
- The left list shows the requests in the file. **Send** runs the selected one together with the
  requests it chains on (`{{login.response.body.$.token}}`); **Send all** runs every request in order.
- **Environment** picks a set of variables from `http-client.env.json` (and `http-client.private.env.json`)
  in the same folder as the file.
- The right pane shows each response: a colored status badge (2xx green, 3xx orange, 4xx red), time, size and
  content type, with **Body** and **Headers** tabs. JSON bodies are shown as a syntax-colored tree: click a
  `{` / `[` line to collapse or expand it. **Raw** shows the body exactly as received; **Copy** copies what the
  current tab shows. A failing request shows its error and does not stop the rest.

See [../docs/features.md](../docs/features.md) for the `.http` syntax, variables and chaining.

## Run

```
./gradlew :desktop:run          # macOS / Linux
gradlew.bat :desktop:run        # Windows
```

## Build installers

`jpackage` cannot cross-compile, so build each installer on the OS it targets. The JDK 21 toolchain is
used automatically.

| OS | Command | Output (under `desktop/build/compose/binaries/main/`) | Extra requirement |
|---|---|---|---|
| macOS | `./gradlew :desktop:packageDmg` | `dmg/HTTP Client-1.0.0.dmg` | none |
| Windows | `gradlew.bat :desktop:packageMsi` | `msi/HTTP Client-1.0.0.msi` | [WiX Toolset 3.x](https://wixtoolset.org/) on `PATH` |
| Linux | `./gradlew :desktop:packageDeb` | `deb/…` | `dpkg`/`fakeroot` |

`:desktop:createDistributable` produces an unpacked app image on any OS without extra tools.

Verified: the macOS DMG builds, the app starts, and headless Compose UI tests (`AppUiTest`) click through
opening requests, choosing an environment, Send with chained prerequisites, error display and the shortcuts.
Not verified: the native Open…/New… file dialogs, the Windows MSI, and how it looks on screen.

## Layout

- `Workspace.kt` — parsing and running requests (no UI).
- `AppState.kt` — Compose state: open file, editor text, selection, environment, outcomes.
- `App.kt` / `Main.kt` — the Compose UI and window (`AppRoot` is the testable content).
- `ResponsePane.kt` / `JsonTree.kt` — the response viewer and the collapsible JSON tree (flattening is pure and tested).
- `SyntaxHighlighting.kt` — colors tokens from `core`'s shared `HttpLineLexer`.

The installer version (`packageVersion` in `build.gradle.kts`) is separate from the project version,
because installers require a major version of at least 1.

## Not yet implemented

Request history, saving responses, and persisting the window,
last file and selected environment between sessions.
