package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.StoredResponse
import com.dan323.httpclient.viewmodel.ResponseDiff
import com.dan323.httpclient.viewmodel.diffResponses
import java.nio.file.Path

/**
 * What the History panel shows: the recorded runs of one file, the run and request being looked at, and the two
 * responses marked for a comparison. Reads go through [store]; a store problem is reported in [error], never thrown.
 * Methods block on disk reads, so UIs call them off the UI thread.
 */
class HistoryState(private val store: HistoryStore) {
    var runs by mutableStateOf<List<HistoryRun>>(emptyList())
        private set
    var selectedRun by mutableStateOf<HistoryRun?>(null)
        private set

    /** Index of the request of [selectedRun] shown in [opened]. */
    var openedIndex by mutableStateOf<Int?>(null)
        private set
    var opened by mutableStateOf<StoredResponse?>(null)
        private set
    var markA by mutableStateOf<StoredResponse?>(null)
        private set
    var markB by mutableStateOf<StoredResponse?>(null)
        private set
    var diff by mutableStateOf<ResponseDiff?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var file: Path? = null

    val canCompare: Boolean get() = markA != null && markB != null

    /**
     * Lists the runs of [file] (newest first). A different file clears the selection and the marks; the same file
     * ([keepSelection], after a new run was recorded) keeps them and the selected run when it still exists.
     */
    fun load(file: Path?, keepSelection: Boolean = false) {
        val same = keepSelection && this.file == file
        this.file = file
        if (!same) { selectedRun = null; openedIndex = null; opened = null; markA = null; markB = null; diff = null }
        error = null
        runs = if (file == null) emptyList() else try { store.list(file) } catch (e: Exception) {
            error = "Could not read the history: ${e.message ?: e::class.simpleName}"
            emptyList()
        }
        selectedRun = selectedRun?.let { current -> runs.firstOrNull { it.id == current.id } }
        if (selectedRun == null) { openedIndex = null; opened = null }
    }

    fun selectRun(runId: String) {
        selectedRun = runs.firstOrNull { it.id == runId }
        openedIndex = null
        opened = null
    }

    /** Shows the stored response of request [index] of the selected run; a request without one leaves [opened] empty. */
    fun open(index: Int) {
        val run = selectedRun ?: return
        val f = file ?: return
        error = null
        openedIndex = index
        opened = try { store.readResponse(f, run.id, index) } catch (e: Exception) {
            error = "Could not read the stored response: ${e.message ?: e::class.simpleName}"
            null
        }
        if (opened == null && error == null) error = "That request has no stored response."
    }

    fun markOpenedAsA() { opened?.let { markA = it; diff = null } }

    fun markOpenedAsB() { opened?.let { markB = it; diff = null } }

    fun compare() {
        val a = markA ?: return
        val b = markB ?: return
        diff = diffResponses(a.response, b.response)
    }

    fun closeCompare() { diff = null }
}
