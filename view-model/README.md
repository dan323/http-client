# view-model

UI-toolkit-free view logic shared by `desktop` and `intellij-plugin`: `flattenJson`/`parseJsonContainer` (collapsible JSON tree rows),
`jsonSpans` (colour spans for pretty-printed JSON), `statusClass`/`statusText`, `sizeLabel` and `durationLabel`.
`RunController` runs the request at a caret/index/all with its dependencies and keeps one `RequestSession` per (file, environment, variables) so chained prerequisites run once; `RunController.forget(file)` drops one file's sessions, `resetChain()` all of them; `OutcomeDisplay` turns a `RunOutcome` into status class, labels and pretty-or-raw body. It depends on `core` only (sessions are supplied by the front end, e.g. from `adapters.SessionFactory`).
No Compose, Swing or IDE classes. It uses kotlinx-serialization-json, which the IntelliJ plugin build excludes from its zip.
