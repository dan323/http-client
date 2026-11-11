package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.StoredResponse
import com.dan323.httpclient.viewmodel.ResponseDiff
import com.dan323.httpclient.viewmodel.diffStoredResponses
import java.nio.file.Path

/**
 * What the History panel shows: the recorded runs of one file, the run and request being looked at, and the two
 * responses marked for a comparison. Reads go through [store]; a store problem is reported in [error], never thrown.
 * Methods block on disk reads, so UIs call them off the UI thread; a read that finishes after the file, run or
 * request it was started for has changed is discarded.
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

    // A read publishes its result only if no newer one has started: loads count in loadGeneration, a changed
    // selection (run, request, file) in selectionGeneration.
    private var loadGeneration = 0L
    private var selectionGeneration = 0L

    val canCompare: Boolean get() = markA != null && markB != null

    /**
     * Lists the runs of [file] (newest first). A different file clears the selection and the marks; the same file
     * ([keepSelection], after a new run was recorded) keeps them and the selected run when it still exists.
     */
    fun load(file: Path?, keepSelection: Boolean = false) {
        val generation = synchronized(this) {
            val same = keepSelection && this.file == file
            this.file = file
            if (!same) { selectedRun = null; openedIndex = null; opened = null; markA = null; markB = null; diff = null; selectionGeneration++ }
            error = null
            ++loadGeneration
        }
        var problem: String? = null
        val listed = if (file == null) emptyList() else try { store.list(file) } catch (e: Exception) {
            problem = "Could not read the history: ${e.message ?: e::class.simpleName}"
            emptyList()
        }
        synchronized(this) {
            if (generation != loadGeneration) return   // a newer load (another file, or a refresh) owns the state
            runs = listed
            error = problem
            val kept = selectedRun?.let { current -> listed.firstOrNull { it.id == current.id } }
            if (kept == null && selectedRun != null) { opened = null; openedIndex = null; selectionGeneration++ }
            selectedRun = kept
        }
    }

    @Synchronized
    fun selectRun(runId: String) {
        selectedRun = runs.firstOrNull { it.id == runId }
        openedIndex = null
        opened = null
        selectionGeneration++
    }

    /** Shows the stored response of request [index] of the selected run; a request without one leaves [opened] empty. */
    fun open(index: Int) {
        val run: HistoryRun
        val f: Path
        val generation: Long
        synchronized(this) {
            run = selectedRun ?: return
            f = file ?: return
            error = null
            openedIndex = index
            opened = null
            generation = ++selectionGeneration
        }
        var problem: String? = null
        val stored = try { store.readResponse(f, run.id, index) } catch (e: Exception) {
            problem = "Could not read the stored response: ${e.message ?: e::class.simpleName}"
            null
        }
        synchronized(this) {
            if (generation != selectionGeneration) return   // the selection moved on while this was reading
            opened = stored
            error = problem ?: if (stored == null) "That request has no stored response." else null
        }
    }

    @Synchronized
    fun markOpenedAsA() { opened?.let { markA = it; diff = null } }

    @Synchronized
    fun markOpenedAsB() { opened?.let { markB = it; diff = null } }

    @Synchronized
    fun compare() {
        val a = markA ?: return
        val b = markB ?: return
        diff = diffStoredResponses(a, b)
    }

    @Synchronized
    fun closeCompare() { diff = null }
}
