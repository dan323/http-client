# view-model

UI-toolkit-free view logic shared by `desktop` and `intellij-plugin`: `flattenJson`/`parseJsonContainer` (collapsible JSON tree rows),
`jsonSpans` (colour spans for pretty-printed JSON), `statusClass`/`statusText`, `sizeLabel` and `durationLabel`.
No Compose, Swing or IDE classes. It uses kotlinx-serialization-json, which the IntelliJ plugin build excludes from its zip.
