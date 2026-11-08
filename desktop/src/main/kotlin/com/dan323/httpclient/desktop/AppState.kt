package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.viewmodel.RunCancellation
import com.dan323.httpclient.viewmodel.RunControls
import com.dan323.httpclient.viewmodel.Tracked
import com.dan323.httpclient.viewmodel.cancelledLabel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class AppState(
    private val runner: DesktopRunner = DesktopRunner(),
    private val settings: SettingsPersistence? = null,
) : AutoCloseable {
    var file by mutableStateOf<Path?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var dirty by mutableStateOf(false)
        private set
    var environments by mutableStateOf<List<String>>(emptyList())
        private set
    private var chosenEnvironment by mutableStateOf<String?>(null)
    var environment: String?
        get() = chosenEnvironment
        set(value) {
            chosenEnvironment = value
            file?.let { f -> settings?.update { it.withEnvironment(canonical(f), value) } }
        }
    var selected by mutableStateOf(0)
    var outcomes by mutableStateOf<List<RunOutcome>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** True when the last run was stopped with [cancel]; cleared when the next run starts or another file is opened. */
    var cancelled by mutableStateOf(false)
        private set

    /** What the run buttons offer now; the shared rule for Send, Cancel and the refusals while running. */
    val controls: RunControls get() = RunControls(running, file != null)

    /** `Cancelled (1 request had finished)` after a cancelled run, else null. A cancelled run is not an error. */
    val cancelledLabel: String? get() = if (cancelled) cancelledLabel(outcomes.size) else null

    /** Recently opened files, most recent first; entries whose file is missing right now (deleted, drive absent) are hidden but stay stored. */
    var recentFiles by mutableStateOf<List<Path>>(emptyList())
        private set

    init {
        settings?.let { s ->
            recentFiles = existingRecents(s)
            message = s.loadWarning
        }
    }

    /** Request list for [text], parsed when the text changes rather than on every recomposition. */
    var requests by mutableStateOf<List<RequestTemplate>>(emptyList())
        private set
    var parseError by mutableStateOf<String?>(null)
        private set

    /** Held for the whole of [run] and of a file switch, so the two never overlap and runs never overlap each other. */
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private val cancellation = RunCancellation()

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
        cancelled = false
        environments = emptyList()
        chosenEnvironment = null   // the choice belongs to the previous file's environments
        val key = canonical(path)
        settings?.update { it.opened(key) }
        recentFiles = settings?.let { existingRecents(it) } ?: recentFiles
        refreshEnvironments()
        // Restore the remembered environment only if it still exists; never an error for a vanished name.
        settings?.current?.environments?.get(key)?.takeIf { it in environments }?.let { chosenEnvironment = it }
    }

    /** Opens the last file of the previous session. A file that is gone is skipped without a message: the app starts empty. */
    fun restoreLastFile() {
        val last = settings?.current?.lastFile?.let { Path.of(it) } ?: return
        if (!Files.isRegularFile(last)) return
        exclusive("open a file") { openLocked(last) }
    }

    /** Opens an entry of the recent list; a file that has vanished is dropped from the list with a short message. */
    fun openRecent(path: Path) {
        if (Files.isRegularFile(path)) { open(path); return }
        var removed = false
        exclusive("update the recent files") {
            settings?.update { it.withoutRecent(canonical(path)).withoutRecent(path.toString()) }
            recentFiles = recentFiles.filter { it != path }
            removed = true
        }
        if (removed) message = "${path.fileName} no longer exists and was removed from the recent files."   // after the guard, which clears the message
    }

    private fun existingRecents(s: SettingsPersistence) = s.current.recentFiles.map { Path.of(it) }.filter { Files.isRegularFile(it) }

    /** The window geometry of the previous session, if any. */
    fun savedWindow(): com.dan323.httpclient.core.port.out.WindowSettings? = settings?.current?.window

    /** Remembers the window geometry for the next start. */
    fun rememberWindow(window: com.dan323.httpclient.core.port.out.WindowSettings) {
        settings?.update { it.withWindow(window) }
    }

    /** Ignored while a run or file switch is in progress, so the buffer cannot diverge from what is being sent. */
    fun edit(newText: String) {
        if (busy.get()) return
        assignText(newText)
        dirty = true
    }

    /** Returns false (with [message] set) when the file could not be written; refused while a run is in progress. */
    fun save(): Boolean {
        if (!busy.compareAndSet(false, true)) {
            message = "Cannot save while a request is running."
            return false
        }
        try {
            val path = file ?: return true
            if (!write(path, text)) return false
            message = null
            refreshQuietly()
            return true
        } finally {
            busy.set(false)
        }
    }

    /** Writes [t]; [dirty] is cleared only if the buffer still equals what was written. False (with [message]) on failure. */
    private fun write(path: Path, t: String): Boolean = try {
        Files.writeString(path, t)
        if (text == t) dirty = false
        true
    } catch (e: Exception) {
        message = e.message ?: e::class.simpleName
        false
    }

    /** An environment-file error is reported but never counts as a failed write or blocks a run. */
    private fun refreshQuietly() {
        try { refreshEnvironments() } catch (e: Exception) { message = e.message ?: e::class.simpleName }
    }

    /** Runs the selected request (with prerequisites), or all requests when [all]. Blocking. */
    fun run(all: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val path = file ?: return
            cancellation.reset()
            cancelled = false
            running = true
            synchronized(lock) { outcomes = emptyList() }   // never leave the previous run's results next to a new error
            val t = text   // one snapshot for the write and the run
            if (!write(path, t)) return   // keep the save error visible instead of running stale text
            message = null
            refreshQuietly()
            val index = selected
            if (!all && requests.isNotEmpty() && index !in requests.indices) {
                message = "No request is selected (the file has ${requests.size}); pick one from the list."
                return
            }
            val tracked = cancellation.track {
                runner.execute(path, t, if (all) null else index, environment) { o -> synchronized(lock) { outcomes = outcomes + o } }
            }
            // Outcomes that finished before the cancel stay; the cancelled request is not remembered (see RunController).
            if (tracked is Tracked.Cancelled) cancelled = true
        } catch (e: Exception) {
            message = e.message ?: e::class.simpleName
        } finally {
            running = false
            busy.set(false)
        }
    }

    /** Stops the running request; does nothing when none is running. The run ends shortly after with [cancelled] set. */
    fun cancel() {
        if (running) cancellation.cancel()
    }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() = runner.resetChain()

    override fun close() {
        try { settings?.close() } finally { runner.close() }
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

/** The key under which a file is remembered: its real path when it exists, else its normalized absolute path. */
internal fun canonical(path: Path): String = try {
    path.toRealPath().toString()
} catch (_: Exception) {
    path.toAbsolutePath().normalize().toString()
}
