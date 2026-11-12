package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Path

/**
 * The state of one open `.http` file: its text and the requests parsed from it, the selected request, the environment
 * choice and the results of the last run. [AppState] holds the documents and everything shared between them
 * (the run lock, settings, history), and forwards to the active one.
 */
internal class OpenDocument(val file: Path, content: String) {
    var text by mutableStateOf(content)
        private set
    var dirty by mutableStateOf(false)

    /** Request list for [text], parsed when the text changes rather than on every recomposition. */
    var requests by mutableStateOf(Workspace.parse(content))
        private set
    var parseError by mutableStateOf(Workspace.parseError(content))
        private set
    var selected by mutableStateOf(0)

    /** Environment names found for this file; the choice belongs to them and is forgotten when the file is opened again. */
    var environments by mutableStateOf<List<String>>(emptyList())
    var chosenEnvironment by mutableStateOf<String?>(null)
    var outcomes by mutableStateOf<List<RunOutcome>>(emptyList())

    /** True when the last run was stopped; cleared when the next run starts. */
    var cancelled by mutableStateOf(false)

    fun assignText(value: String) {
        text = value
        requests = Workspace.parse(value)
        parseError = Workspace.parseError(value)
    }
}
