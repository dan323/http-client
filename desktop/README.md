# desktop

Standalone desktop front end for the HTTP client, built with
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for Desktop.
It runs on Windows, macOS and Linux and reuses `core` and `adapters` unchanged.

## What it does

- **Open…** / **New…** a `.http` file. The text is editable in the middle pane; **Save** writes it back.
  Running a request saves first.
- The left list shows the requests in the file. **Send** runs the selected one together with the
  requests it chains on (`{{login.response.body.$.token}}`); **Send all** runs every request in order.
- **Environment** picks a set of variables from `http-client.env.json` (and `http-client.private.env.json`)
  in the same folder as the file.
- The right pane shows each response: status, timing, size and body (JSON is pretty-printed).
  Tick **Headers** to include response headers. A failing request shows its error and does not stop the rest.

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

Verified: the macOS DMG builds and `AppStateTest` passes. The Windows MSI has not been built yet,
and the UI has not been exercised by hand.

## Layout

- `Workspace.kt` — parsing and running requests (no UI).
- `AppState.kt` — Compose state: open file, editor text, selection, environment, outcomes.
- `App.kt` / `Main.kt` — the Compose UI and window.

The installer version (`packageVersion` in `build.gradle.kts`) is separate from the project version,
because installers require a major version of at least 1.

## Not yet implemented

Syntax highlighting in the editor, request history, saving responses, and persisting the window,
last file and selected environment between sessions.
