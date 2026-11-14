package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.viewmodel.EnvironmentDraft
import com.dan323.httpclient.viewmodel.EnvironmentEditor
import com.dan323.httpclient.viewmodel.SaveOutcome
import com.dan323.httpclient.viewmodel.needsReload
import com.dan323.httpclient.viewmodel.problemLines
import com.dan323.httpclient.viewmodel.saveMessage

/**
 * What the environment editor panel shows: the draft of [EnvironmentEditor] as Compose state, the line after the last
 * save and the problems that stopped it. [load] and [save] read and write files, so UIs call them off the UI thread.
 */
class EnvironmentEditorState(private val editor: EnvironmentEditor) {
    var draft by mutableStateOf(EnvironmentDraft.EMPTY)
        private set

    /** Why the files could not be loaded, or null. While set there is nothing to edit. */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** The result of the last save, or null. */
    var status by mutableStateOf<String?>(null)
        private set

    /** What stopped the last save, one line each. */
    var problems by mutableStateOf<List<String>>(emptyList())
        private set

    /** Whether private values are shown as typed (they are masked by default). */
    var reveal by mutableStateOf(false)

    /** Whether saving would change a file. */
    val dirty: Boolean get() = draft.isDirty

    /** Reads both files again, dropping the draft. */
    fun load() {
        loadError = editor.load()
        draft = editor.draft
        problems = emptyList()
    }

    /** Replaces the draft with [change] of it and forgets the last save's message. */
    fun change(change: (EnvironmentDraft) -> EnvironmentDraft) {
        editor.edit(change)
        draft = editor.draft
        status = null
        problems = emptyList()
    }

    /** Saves the draft; the files are read again when the outcome says the draft no longer matches them. Returns the outcome. */
    fun save(): SaveOutcome {
        val outcome = editor.save()
        status = saveMessage(outcome)
        problems = (outcome as? SaveOutcome.Invalid)?.let { problemLines(editor.draft, it.problems) } ?: emptyList()
        if (needsReload(outcome)) editor.load()
        loadError = editor.loadError   // a save that wrote reloads by itself and may fail doing so
        draft = editor.draft
        return outcome
    }
}
