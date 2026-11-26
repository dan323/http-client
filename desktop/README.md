# desktop

Standalone desktop front end for the HTTP client, built with
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for Desktop.
It runs on Windows, macOS and Linux and reuses `core` and `adapters` unchanged; JSON tree flattening and status/size formatting come from the shared `view-model` module.

## What it does

- **Open…** / **New…** a `.http` file, or pass one at launch (`./gradlew :desktop:run --args="samples/basic.http"`).
  The text is editable in the middle pane with syntax highlighting (methods, URLs, header names, `{{variables}}`,
  comments, `###` separators, `< file` bodies; light and dark palettes). **Save** writes it back; running a request saves first.
- **Problems:** `{{variables}}` that would fail at run time (undefined for the chosen environment, a request that does not exist or has not run yet, a bad dynamic variable, a parse error...) are underlined in the editor (red errors, amber warnings) and listed under it as `Line N: message` with a count; the list is gone when the file is fine. A variable counts as defined when the file defines it before use or the chosen environment has it (no environment chosen: only the file's variables), so choosing an environment clears the undefined-variable problems that it fixes. Messages never contain variable values. Added in T16 (step 1: diagnostics; completion and find/replace follow).
- **Completion:** typing inside an open `{{` offers the names usable there under the editor, each with its kind: variables the file defines before that line, the chosen environment's variables, the requests before it as `name.response.` (kept open so you go on typing `status`, `headers.X` or `body...`) and the dynamic variables. Names that start with what you typed come first, then those that contain it (case ignored), at most 12. Click one, press Tab for the first, or move with Up/Down and press Enter; a plain Enter is a line break, so typing a complete `{{base` and Enter never turns it into `basket`. Esc hides the list until you type or move the caret. The keyboard stays in the editor after a click. Accepting replaces the whole name under the caret (`{{ba|se}}` gives `{{basket}}`, not `{{basket}}se}}`) and closes the reference with `}}` unless it is already closed. Templates with placeholders insert only what is real: `$random.integer(from, to)` gives `$random.integer()` with the caret between the parentheses, and `$env.NAME` / `$processEnv NAME` stop after the `.` / the space. Nothing is offered outside `{{ }}`, while a request runs, or with a selection.
- **Find and replace:** **Find** (next to Save) or Ctrl/Cmd+F in the editor opens a bar above it: the text to find (case ignored unless **Match case**), a `1 of 3` / `No matches` count, **Prev** / **Next** (Enter / Shift+Enter in the box; Next goes to the first match after the caret and wraps; counting, marking and stepping cover the first 5,000 matches, and the count then reads `5000+`), and **Close** (Esc). The matches (up to the first 5,000) are marked in the editor and the one the caret is on is marked stronger. **Replace** selects the next match first, then replaces the selected one and moves to the one after it (replacing `a` by `aa` does not loop); **Replace all** replaces every match, with no limit, and says how many (`Replaced 3 matches`). Replacing edits the text like typing does (it is not saved until you save) and is refused while a request runs. The search text stays when another tab comes on screen.
- The app remembers the open files (tabs) and the one on screen, the last file, its environment, the window geometry and the 10 most recent files (**Recent files**) in `settings.json` in the user config folder; see [../docs/features.md](../docs/features.md#desktop-app) for the location and failure behaviour. Only paths, names and geometry are stored.
- Shortcuts: **Ctrl/Cmd+S** saves, **Ctrl/Cmd+Enter** sends the selected request, **Esc** cancels the running request (ignored when none runs).
- The left list shows the requests in the file, with each method coloured as in the editor. **Send** runs the selected one together with the
  requests it chains on (`{{login.response.body.$.token}}`); **Send all** runs every request in order.
- **Environment** picks a set of variables from the nearest `http-client.env.json` (and the `http-client.private.env.json` beside it),
  searching upward from the file's folder like the CLI and the plugin. The default is "none" (variables from the file only); a name that no longer exists is an error.
- **Edit environments** opens an editor for the environment files in the right pane: tables of variables per environment with a **Private** flag (saved in `http-client.private.env.json`, masked until **Show private values**), **Add environment** / **Add variable**, **Save**, **Reload**. Details: [../docs/features.md](../docs/features.md#desktop-app).
- Responses of earlier requests (a login) are remembered per file and environment, so **Send** does not resend them; **Reset chain** clears them and briefly shows `Chain reset` beside the button.
- **Cancel** (next to **Send**, enabled only while a request runs) stops it: the response pane shows `Cancelled` instead of an error, responses that already arrived stay, and the cancelled request is not remembered, so the next run sends the chain again. A small spinner beside the buttons shows while a request runs.
- **Linked response** (shown when the selected request has a `<>` line) opens History on the latest response recorded for that link; without one it says to run the request first.
- **Record history** (off by default) stores each run of the open file in `.http-history` next to it; **History** then lists the runs and shows a stored response, and **Mark as A** / **Mark as B** with **Compare** shows the differences between two of them (status, headers, body). Stored responses are raw: keep the folder out of version control.
- While a request runs, editing, saving, opening and creating files are disabled; a run saves the file first and a save error stops it.
- The right pane shows each response: a colored status badge (2xx green, 3xx orange, 4xx red), time, size and
  content type, with **Body** and **Headers** tabs. JSON bodies are shown as a syntax-colored tree: click a
  `{` / `[` line to collapse or expand it. **Raw** shows the body exactly as received; **Copy** copies what the
  current tab shows. **Search** (on the Body tab) opens a search box: matches are highlighted in the body shown as text (the JSON tree is replaced while you search), with a count and **Prev** / **Next** (Enter and Shift+Enter in the box do the same); case is ignored. A response whose `Content-Type` is `image/png`, `jpeg`, `gif`, `webp` or `bmp` opens on a **Preview** tab with the picture (scaled to fit, with type, pixel size and bytes); an image over 20 MB or 40 million pixels, or one that cannot be decoded, says so and leaves the raw body on the Body tab. SVG is not previewed. **Save body** writes the body exactly as received (any bytes) to a file you choose, offering a name from `Content-Disposition` or the URL. A failing request shows its error and does not stop the rest.
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
and sends requests. The macOS DMG and Linux DEB (release `0.6.0`) were installed and run by hand too.
Known UI problems are listed in [../docs/known-limitations.md](../docs/known-limitations.md).

## Layout

- `Workspace.kt` — parsing, environment names and `DesktopRunner` (no UI).
- `AppState.kt` — Compose state: the open files (tabs), the active one, the run guard, settings and history.
- `FolderPanel.kt` — the folder view (the panel and `FolderTreeState`, which lists off the UI thread); the listing rules and the tree itself are `FolderListing` and `FolderTree` in `view-model`.
- `OpenDocument.kt` — the state of one open file: text, requests, selection, environment, results.
- `App.kt` / `Main.kt` — the Compose UI and window (`AppRoot` is the testable content).
- `DesktopRunner` (in `Workspace.kt`) runs requests through the shared `RunController` (one session per file and environment, so a login request runs once; closed with the window).
- `ResponsePane.kt` — the response viewer and the collapsible JSON tree; the pure logic (JSON flattening in `JsonTree.kt`, status/size/duration labels) lives in the shared `view-model` module and is tested there. Durations are shown as `1.5 s` (or `2m 5s`) instead of `1500 ms`, and numbers are always formatted with `.` regardless of the system locale.
- `SyntaxHighlighting.kt` — colors tokens from `core`'s shared `HttpLineLexer`.

The installer version (`packageVersion` in `build.gradle.kts`) is separate from the project version,
because installers require a major version of at least 1; its major is the project's major plus one.

## Environment and file access

`{{$env.NAME}}` reads nothing by default: allow names with `-Dhttpclient.allowEnv=NAME,NAME` (JVM option) or the environment variable `HTTPC_ALLOW_ENV=NAME,NAME`; see [docs/features.md](../docs/features.md).

`< path` bodies are confined to the `.http` file's folder; allow more with `-Dhttpclient.allowFileRoots=/abs/dir` or `HTTPC_ALLOW_FILE_ROOTS=/abs/dir` (absolute paths, `~` expanded). See [docs/features.md](../docs/features.md).
