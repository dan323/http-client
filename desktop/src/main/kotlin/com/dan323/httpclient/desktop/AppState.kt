package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.application.RunOutcome
import java.nio.file.Files
import java.nio.file.Path

class AppState {
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

    val requests get() = Workspace.parse(text)

    fun open(path: Path) = guarded {
        file = path
        text = Files.readString(path)
        dirty = false
        selected = 0
        outcomes = emptyList()
        refreshEnvironments()
    }

    fun create(path: Path) = guarded {
        if (!Files.exists(path)) Files.writeString(path, "GET https://httpbin.org/get\n")
        open(path)
    }

    fun edit(newText: String) {
        text = newText
        dirty = true
    }

    fun save() = guarded {
        file?.let { Files.writeString(it, text); dirty = false; refreshEnvironments() }
    }

    /** Runs the selected request (with prerequisites), or all requests when [all]. Blocking. */
    fun run(all: Boolean) {
        val path = file ?: return
        if (running) return
        save()
        running = true
        outcomes = emptyList()
        message = null
        try {
            Workspace.execute(path, text, if (all) null else selected, environment) { outcomes = outcomes + it }
        } catch (e: Exception) {
            message = e.message ?: e::class.simpleName
        } finally {
            running = false
        }
    }

    /** Runs a file chooser, turning any failure (no display, dialog error) into a visible message. */
    fun pick(chooser: () -> Path?): Path? = try {
        chooser()
    } catch (e: Throwable) {
        message = "Could not show the file dialog: ${e.message ?: e::class.simpleName}"
        null
    }

    private fun refreshEnvironments() {
        val path = file ?: return
        environments = Workspace.environmentNames(path)
        if (environment !in environments) environment = null
    }

    private fun guarded(block: () -> Unit) {
        try { block(); message = null } catch (e: Exception) { message = e.message ?: e::class.simpleName }
    }
}
