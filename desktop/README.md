# desktop

Standalone desktop front end for the HTTP client, built with
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for Desktop.
It runs on Windows, macOS and Linux and reuses `core` and `adapters` unchanged; JSON tree flattening and status/size formatting come from the shared `view-model` module.

## What it does

- **Open…** / **New…** a `.http` file, or pass one at launch (`./gradlew :desktop:run --args="samples/basic.http"`).
  The text is editable in the middle pane with syntax highlighting (methods, URLs, header names, `{{variables}}`,
  comments, `###` separators, `< file` bodies; light and dark palettes). **Save** writes it back; running a request saves first.
- The app remembers the last file, its environment, the window geometry and the 10 most recent files (**Recent files**) in `settings.json` in the user config folder; see [../docs/features.md](../docs/features.md#desktop-app) for the location and failure behaviour. Only paths, names and geometry are stored.
- Shortcuts: **Ctrl/Cmd+S** saves, **Ctrl/Cmd+Enter** sends the selected request, **Esc** cancels the running request (ignored when none runs).
- The left list shows the requests in the file, with each method coloured as in the editor. **Send** runs the selected one together with the
  requests it chains on (`{{login.response.body.$.token}}`); **Send all** runs every request in order.
- **Environment** picks a set of variables from the nearest `http-client.env.json` (and the `http-client.private.env.json` beside it),
  searching upward from the file's folder like the CLI and the plugin. The default is "none" (variables from the file only); a name that no longer exists is an error.
- Responses of earlier requests (a login) are remembered per file and environment, so **Send** does not resend them; **Reset chain** clears them and briefly shows `Chain reset` beside the button.
- **Cancel** (next to **Send**, enabled only while a request runs) stops it: the response pane shows `Cancelled` instead of an error, responses that already arrived stay, and the cancelled request is not remembered, so the next run sends the chain again. A small spinner beside the buttons shows while a request runs.
- **Record history** (off by default) stores each run of the open file in `.http-history` next to it; **History** then lists the runs and shows a stored response, and **Mark as A** / **Mark as B** with **Compare** shows the differences between two of them (status, headers, body). Stored responses are raw: keep the folder out of version control.
- While a request runs, editing, saving, opening and creating files are disabled; a run saves the file first and a save error stops it.
- The right pane shows each response: a colored status badge (2xx green, 3xx orange, 4xx red), time, size and
  content type, with **Body** and **Headers** tabs. JSON bodies are shown as a syntax-colored tree: click a
  `{` / `[` line to collapse or expand it. **Raw** shows the body exactly as received; **Copy** copies what the
  current tab shows. A failing request shows its error and does not stop the rest.
- Assertion results (`# @assert`) appear under the status line: a summary (`Assertions: 2 passed, 1 failed`) and a
  green `PASS` / red `FAIL ... -- reason` line for each; a failed request notes how many assertions were not run.

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
| macOS | `./gradlew :desktop:packageDmg` | `dmg/HTTP Client-<installer version>.dmg` | none |
| Windows | `gradlew.bat :desktop:packageMsi` | `msi/HTTP Client-<installer version>.msi` | [WiX Toolset 3.x](https://wixtoolset.org/) on `PATH` |
| Linux | `./gradlew :desktop:packageDeb` | `deb/…` | `dpkg`/`fakeroot` |

`:desktop:createDistributable` produces an unpacked app image on any OS without extra tools.

The installer version is derived from the project version (`0.5.0` becomes `1.5.99`, `0.5.0-rc1` becomes `1.5.1`, `1.0.0` becomes `2.0.99`; the major is shifted by one; see
`desktop/build.gradle.kts`), so a newer installer upgrades an older one. The runtime in the installers is a jlink-trimmed
JRE; `./gradlew :desktop:checkRuntimeModules` (run in CI) fails if the image lacks a module reported by `jdeps`.
Modules loaded only through reflection or `ServiceLoader` may not be detected; add those to `extraRuntimeModules` in
`desktop/build.gradle.kts` when found.

Verified: headless Compose UI tests (`AppUiTest`) click through opening requests, choosing an environment, Send with
chained prerequisites, error display and the shortcuts. By hand (release `0.5.0-rc1`): the Windows MSI installs, starts
and sends requests. The macOS DMG and Linux DEB are built by the release workflow but have not been installed or run.
Known UI problems are listed in [../docs/known-limitations.md](../docs/known-limitations.md).

## Layout

- `Workspace.kt` — parsing, environment names and `DesktopRunner` (no UI).
- `AppState.kt` — Compose state: open file, editor text, selection, environment, outcomes, the run guard.
- `App.kt` / `Main.kt` — the Compose UI and window (`AppRoot` is the testable content).
- `DesktopRunner` (in `Workspace.kt`) runs requests through the shared `RunController` (one session per file and environment, so a login request runs once; closed with the window).
- `ResponsePane.kt` — the response viewer and the collapsible JSON tree; the pure logic (JSON flattening in `JsonTree.kt`, status/size/duration labels) lives in the shared `view-model` module and is tested there. Durations are shown as `1.5 s` (or `2m 5s`) instead of `1500 ms`, and numbers are always formatted with `.` regardless of the system locale.
- `SyntaxHighlighting.kt` — colors tokens from `core`'s shared `HttpLineLexer`.

The installer version (`packageVersion` in `build.gradle.kts`) is separate from the project version,
because installers require a major version of at least 1; its major is the project's major plus one.

`{{$env.NAME}}` reads nothing by default: allow names with `-Dhttpclient.allowEnv=NAME,NAME` (JVM option) or the environment variable `HTTPC_ALLOW_ENV=NAME,NAME`; see [docs/features.md](../docs/features.md).

`< path` bodies are confined to the `.http` file's folder; allow more with `-Dhttpclient.allowFileRoots=/abs/dir` or `HTTPC_ALLOW_FILE_ROOTS=/abs/dir` (absolute paths, `~` expanded). See [docs/features.md](../docs/features.md).
