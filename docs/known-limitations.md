# Known limitations

Issues found in the R1-R13 review (all fixed or deliberately left; the history is in [../CHANGELOG.md](../CHANGELOG.md)) and
accepted for now. None blocks a release. Fix one when it starts to matter, then delete its line.
Related: [competitive-gaps.md](competitive-gaps.md) (what to add), [PLAN.md](PLAN.md) (open tasks).

## Execution and security

- **File confinement can be raced.** Someone with write access inside an allowed folder can swap a parent directory for a symlink between the check and the read.
- **Silently dropped headers.** A user-set `Host`, `Content-Length`, `Connection`, `Expect` or `Upgrade` header is dropped by the JDK client (documented, not warned about).
- **Public Suffix List is a snapshot.** Cookie `Domain` checks use the list bundled in `core` (2026-11-06); suffixes added later are not recognised until the file is refreshed (procedure in [design-notes.md](design-notes.md#cookie-jar), Cookie jar). Internationalised (non-ASCII) host names are compared as written, not converted to punycode.

## CLI

- `samePath` compares file names case-insensitively within one folder, so two reports that differ only by case are rejected even on case-sensitive file systems (conservative).

## Front ends

- **Plugin:** the VFS listener (forget on delete/move), the gutter icon, and the tool window have no automated tests (they need an IDE). The plugin was checked by hand on IntelliJ IDEA Ultimate 2026.3 (T21; the recorded checks cover the gutter icon, the tool window and the reset on edit, not delete/move); a real Community install and the 2026.1 baseline were not.
- **Plugin, known bug (not a release blocker):** in the default keymap the shortcut `Ctrl+Alt+Enter` for *Run HTTP Request* does not work: it is also bound to the built-in *Insert line above*, which wins, so the key inserts a new line. Use the gutter run icon next to the request, or put the caret in the request and use *Run HTTP Request* in the editor context menu. You can also free the keys by removing or changing the *Insert Line Above* binding in *Settings > Keymap* (not tried), or assign another shortcut to *Run HTTP Request* there; the plugin's default will change in a later release. Found while verifying the plugin (T21).
- **Desktop, UI problems found checking the Windows installer (not release blockers, planned after 0.5.0):** the *Forget responses* button text does not fit the button (the plugin calls it *Reset chain*); the environment dropdown opens far from the picker instead of under it; the request list is plain text, without the method colours of the editor.
- **Desktop:** there is no way to edit `http-client.env.json` in the app (in IntelliJ the file is just another editor tab); open it in another editor.
- **Desktop installers:** the macOS DMG and Linux DEB are built by the release workflow but have not been installed or run by anyone; the installers are unsigned.
- **Desktop and plugin:** assertion results are not shown (only the CLI prints them; T17, T22).
- **Desktop and plugin:** no UI for the `$env` and file-root allowlists (system property / environment variable only).

## Mock server (test fixture, low priority)

- It binds in the constructor instead of `start()`, and a non-numeric port argument silently falls back to 8080.
- Not looked at again: bad percent-escape handling, the default single-threaded executor with an unbounded body read, and repeated query keys as lists.

## Deliberate deviations from the JetBrains HTTP Client

Decided in the review, not bugs:

- `< path` file bodies are confined to the `.http` file's folder by default; an allowlist (`--allow-file-root`, `HTTPC_ALLOW_FILE_ROOTS`) widens it. JetBrains does not confine.
- An unknown or mistyped method (`GTE http://x`) is a parse error with the line number, not a URL.
- `> {% ... %}` response handlers are skipped with a warning, not run.
- The "(none)" environment really means none: no environment variables are used, only file variables and the built-in dynamic variables (`{{$uuid}}`, `{{$randomInt}}`, ...), and there is never a silent fallback to another environment.
