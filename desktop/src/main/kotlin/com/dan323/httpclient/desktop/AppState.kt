package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class AppState(private val runner: DesktopRunner = DesktopRunner()) : AutoCloseable {
    var file by mutableStateOf<Path?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var dirty by mutableStateOf(false)
        private set
    var environments by mutableStateOf<List<String>>(emptyList())
        private set
    var environment by mutableStateOf<String?>(null)
    var selected by mutableStateOf(0)
    var outcomes by mutableStateOf<List<RunOutcome>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Request list for [text], parsed when the text changes rather than on every recomposition. */
    var requests by mutableStateOf<List<RequestTemplate>>(emptyList())
        private set
    var parseError by mutableStateOf<String?>(null)
        private set

    /** Held for the whole of [run] and of a file switch, so the two never overlap and runs never overlap each other. */
    private val busy = AtomicBoolean(false)
    private val lock = Any()

    private fun assignText(value: String) {
        text = value
        requests = Workspace.parse(value)
        parseError = Workspace.parseError(value)
    }

    fun open(path: Path) = exclusive("open a file") { openLocked(path) }

    fun create(path: Path) = exclusive("create a file") {
        if (!Files.exists(path)) Files.writeString(path, "GET https://httpbin.org/get\n")
        openLocked(path)
    }

    private fun openLocked(path: Path) {
        val content = Files.readString(path)   // read first: a failure leaves the current file and text untouched
        file = path
        assignText(content)
        dirty = false
        selected = 0
        outcomes = emptyList()
        environments = emptyList()
        environment = null   // the choice belongs to the previous file's environments
        refreshEnvironments()
    }

    fun edit(newText: String) {
        assignText(newText)
        dirty = true
    }

    /** Returns false (with [message] set) when the file could not be written. */
    fun save(): Boolean = guarded {
        file?.let { Files.writeString(it, text); dirty = false; refreshEnvironments() }
    }

    /** Runs the selected request (with prerequisites), or all requests when [all]. Blocking. */
    fun run(all: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val path = file ?: return
            running = true
            if (!save()) return   // keep the save error visible instead of running stale text
            synchronized(lock) { outcomes = emptyList() }
            val index = selected
            if (!all && requests.isNotEmpty() && index !in requests.indices) {
                message = "No request is selected (the file has ${requests.size}); pick one from the list."
                return
            }
            message = null
            runner.execute(path, text, if (all) null else index, environment) { o -> synchronized(lock) { outcomes = outcomes + o } }
        } catch (e: Exception) {
            message = e.message ?: e::class.simpleName
        } finally {
            running = false
            busy.set(false)
        }
    }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() = runner.resetChain()

    override fun close() = runner.close()

    /** Runs a file chooser, turning any failure (no display, dialog error) into a visible message. */
    fun pick(chooser: () -> Path?): Path? = try {
        chooser()
    } catch (e: Throwable) {
        message = "Could not show the file dialog: ${e.message ?: e::class.simpleName}"
        null
    }

    private fun refreshEnvironments() {
        val path = file ?: return
        try {
            environments = Workspace.environmentNames(path)
        } catch (e: Exception) {
            environments = emptyList()   // never keep another file's (or a now-broken) list
            throw e
        }
        // A chosen name that vanished is kept: running reports "Unknown environment" instead of silently running without one.
    }

    private fun exclusive(what: String, block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            message = "Cannot $what while a request is running."
            return
        }
        try { guarded(block) } finally { busy.set(false) }
    }

    private fun guarded(block: () -> Unit): Boolean = try {
        block(); message = null; true
    } catch (e: Exception) {
        message = e.message ?: e::class.simpleName; false
    }
}
