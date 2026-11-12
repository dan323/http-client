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

    /** True when [text] has edits that are not in the file; set by [edit], cleared by [saved]. */
    var dirty by mutableStateOf(false)
        private set

    /** Request list for [text], parsed when the text changes rather than on every recomposition. */
    var requests by mutableStateOf(Workspace.parse(content))
        private set
    var parseError by mutableStateOf(Workspace.parseError(content))
        private set
    var selected by mutableStateOf(0)

    /** Environment names found for this file; the choice belongs to them and is forgotten when the file is opened again. */
    var environments by mutableStateOf<List<String>>(emptyList())
        private set
    var chosenEnvironment by mutableStateOf<String?>(null)

    /** Results of the last run. Written only by [AppState], under its lock, while a run is in progress. */
    var outcomes by mutableStateOf<List<RunOutcome>>(emptyList())
        private set

    /** True when the last run was stopped; cleared when the next run starts. */
    var cancelled by mutableStateOf(false)
        private set

    /** Replaces the text with the user's edit; the document is then [dirty]. */
    fun edit(value: String) {
        parse(value)
        dirty = true
    }

    /** [written] is now in the file; the document is clean only if the buffer still equals it. */
    fun saved(written: String) {
        if (text == written) dirty = false
    }

    fun updateEnvironments(names: List<String>) { environments = names }

    /** Clears the results and the cancelled flag for a new run. */
    fun beginRun() {
        cancelled = false
        outcomes = emptyList()
    }

    fun addOutcome(outcome: RunOutcome) { outcomes = outcomes + outcome }

    fun markCancelled() { cancelled = true }

    private fun parse(value: String) {
        text = value
        requests = Workspace.parse(value)
        parseError = Workspace.parseError(value)
    }
}
