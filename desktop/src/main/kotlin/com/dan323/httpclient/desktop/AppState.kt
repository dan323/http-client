package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.port.out.HistoryStore
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
    private val history: HistoryStore? = null,
) : AutoCloseable {
    /** The open files, in tab order. The editor, the request list and Send work on [active]; the properties below forward to it. */
    internal var documents by mutableStateOf<List<OpenDocument>>(emptyList())
        private set
    private var activeIndex by mutableStateOf(0)

    /** The document on screen; null until a file is opened. */
    internal val active: OpenDocument? get() = documents.getOrNull(activeIndex)

    /** Index of the active tab (0 with no file open). */
    val activeTab: Int get() = activeIndex

    /** The folder picked for the folder view; null means the folder of the file on screen. Remembered between sessions. */
    var chosenFolder by mutableStateOf(settings?.current?.folder?.let { Path.of(it) })
        private set

    /** True when the folder view is shown next to the request list. Remembered between sessions. */
    var folderOpen by mutableStateOf(settings?.current?.folderOpen ?: false)
        private set

    /** The folder the folder view lists: the picked one, else the folder of the file on screen, else none. */
    val folderRoot: Path? get() = chosenFolder ?: active?.file?.toAbsolutePath()?.parent

    /** Shows or hides the folder view. */
    fun showFolder(show: Boolean) {
        folderOpen = show
        settings?.update { it.withFolderOpen(show) }
    }

    /** Lists [folder] in the folder view and shows it. */
    fun chooseFolder(folder: Path) {
        chosenFolder = folder
        folderOpen = true
        settings?.update { it.withFolder(folder.toAbsolutePath().toString()).withFolderOpen(true) }
    }

    /** What a tab shows: its [label] (the file name, with parent folders when two open files share a name) and whether it has unsaved edits. */
    class TabInfo(val label: String, val dirty: Boolean)

    val tabs: List<TabInfo> get() {
        val docs = documents
        val labels = tabLabels(docs.map { it.file })
        return docs.mapIndexed { i, d -> TabInfo(labels[i], d.dirty) }
    }

    /** Guards the tab list and the active index: tabs change from the UI thread (switch, close) and from the thread that opens a file. */
    private val tabsLock = Any()

    /** The document whose run is in progress, if any; its results land there even when another tab is on screen. */
    private var runningDocument by mutableStateOf<OpenDocument?>(null)

    /** True while the run in progress belongs to the tab on screen (other tabs show their own results, not "Sending…"). */
    val runningHere: Boolean get() = running && runningDocument === active

    /** The tab whose close asked about unsaved edits ([confirmClose] / [cancelClose] answer it); null when nothing is asked. */
    private var closing by mutableStateOf<OpenDocument?>(null)
    val closePrompt: TabInfo? get() = closing?.let { d -> tabs.getOrNull(documents.indexOf(d)) }

    /** Shows the tab at [index]. Allowed while a request runs (the run keeps going on its own tab). */
    fun switchTo(index: Int) {
        if (index in documents.indices && index != activeIndex) activate(index)
    }

    /** Puts the tab on screen: the history panel follows it, its environments are read again, and it becomes the file remembered for the next start. */
    private fun activate(index: Int, restoring: Boolean = false) {
        val doc = synchronized(tabsLock) {
            val d = documents.getOrNull(index) ?: return
            activeIndex = index
            if (historyOpen) historyView?.loadInBackground(d.file.toAbsolutePath())
            d
        }
        if (restoring) return   // the start-up restore touches neither the environments nor the recency
        refreshQuietly(doc)
        rememberFile(doc)
    }

    /** Marks [doc]'s file as the last opened one and moves it to the front of the recent files. */
    private fun rememberFile(doc: OpenDocument) {
        settings?.update { it.opened(canonical(doc.file)) }
        recentFiles = settings?.let { existingRecents(it) } ?: recentFiles
        rememberTabs()
    }

    /** Remembers which files are open (in tab order) and which is on screen, for the next start. */
    private fun rememberTabs() {
        val s = settings ?: return
        // The tabs are read inside the change, which the settings run one at a time: the newest state is always the last one stored.
        s.update { current ->
            synchronized(tabsLock) {
                val open = documents.map { canonical(it.file) }
                current.withOpenFiles(open + unavailable.filter { it !in open }, active?.let { canonical(it.file) })
            }
        }
    }

    /** Files of the last session that could not be opened at start (an absent drive, say); kept in the stored list, behind the open tabs, until they open again. */
    private var unavailable: List<String> = emptyList()

    /**
     * Closes the tab at [index]. A tab with unsaved edits is not closed: [closePrompt] asks first. The tab whose request is
     * running cannot be closed.
     */
    fun closeTab(index: Int) {
        val doc = documents.getOrNull(index) ?: return
        if (doc.dirty && !isRunning(doc)) { closing = doc; return }
        remove(doc)
    }

    /** Answers [closePrompt]: saves the file first when [save], then closes the tab; a failed save keeps the tab open with the error in [message]. */
    fun confirmClose(save: Boolean) {
        val doc = closing ?: return
        if (!isRunning(doc) && save && !saveDocument(doc)) return
        closing = null
        remove(doc)
    }

    fun cancelClose() { closing = null }

    private fun isRunning(doc: OpenDocument) = running && runningDocument === doc

    /** Removes [doc] (refused while its request runs). The tab that comes on screen becomes the file remembered for the next start. */
    private fun remove(doc: OpenDocument) {
        val shown = synchronized(tabsLock) {
            if (isRunning(doc)) { message = "Cannot close ${doc.file.fileName} while its request is running."; return }
            val index = documents.indexOf(doc)
            if (index < 0) return
            val wasActive = index == activeIndex
            documents = documents - doc
            activeIndex = if (index < activeIndex) activeIndex - 1 else activeIndex.coerceAtMost((documents.size - 1).coerceAtLeast(0))
            if (wasActive && historyOpen) historyView?.loadInBackground(active?.file?.toAbsolutePath())
            if (wasActive) active else null
        }
        if (shown != null) rememberFile(shown) else rememberTabs()
    }

    val file: Path? get() = active?.file
    val text: String get() = active?.text ?: ""
    val dirty: Boolean get() = active?.dirty ?: false
    val environments: List<String> get() = active?.environments ?: emptyList()
    /** The environment chosen for the open file, remembered between sessions; with no file open there is none and setting it does nothing. */
    var environment: String?
        get() = active?.chosenEnvironment
        set(value) {
            val doc = active ?: return
            doc.chosenEnvironment = value
            settings?.update { it.withEnvironment(canonical(doc.file), value) }
        }
    var selected: Int
        get() = active?.selected ?: 0
        set(value) { active?.selected = value }
    val outcomes: List<RunOutcome> get() = active?.outcomes ?: emptyList()
    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** True when the last run was stopped with [cancel]; cleared when the next run starts; opening a file starts a new document, which is not cancelled. */
    val cancelled: Boolean get() = active?.cancelled ?: false

    /** A short, self-clearing confirmation (e.g. [CHAIN_RESET_NOTICE]); the UI drops it after a few seconds with [clearNotice]. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Changes every time [notice] is set, so the UI can restart its timer when the same text is shown again. */
    var noticeId by mutableStateOf(0)
        private set

    /** The History panel's state; null when this app has no history store. */
    val historyView: HistoryState? = history?.let { HistoryState(it) }

    /** True when the History panel replaces the response pane. */
    var historyOpen by mutableStateOf(false)
        private set

    private var recording by mutableStateOf(settings?.current?.recordHistory ?: false)

    /** Whether ordinary runs are stored in the history (off by default; stored responses are raw). Remembered between sessions. */
    var recordHistory: Boolean
        get() = recording
        set(value) {
            recording = value
            settings?.update { it.withRecordHistory(value) }
        }

    /** Recording needs a history store; without one the option is not offered. */
    val canRecordHistory: Boolean get() = history != null

    /** Shows or hides the History panel; showing it reads the runs of the open file. Blocking (disk). */
    fun showHistory(show: Boolean) {
        historyOpen = show && history != null
        if (historyOpen) historyView?.load(file?.toAbsolutePath())
    }

    /** True when the selected request has a `<>` link (and there is a history store to look it up in). */
    val selectedHasLink: Boolean get() = history != null && requests.getOrNull(selected)?.responseLinks?.isNotEmpty() == true

    /**
     * True when [showLinkedResponse] can run: not while a request runs, and not with unsaved edits, because a link is
     * found by the request's name or start line, which edits can change.
     */
    val canOpenLinked: Boolean get() = selectedHasLink && !running && !dirty

    /** Opens the History panel on the latest response recorded for the selected request's `<>` link. Blocking (disk). */
    fun showLinkedResponse() {
        val view = historyView ?: return
        if (!canOpenLinked) return
        exclusive("open the linked response") {
            val request = requests.getOrNull(selected)?.takeIf { it.responseLinks.isNotEmpty() } ?: return@exclusive
            val abs = file?.toAbsolutePath() ?: return@exclusive
            if (!historyOpen) view.load(abs)   // an open panel already follows the file
            historyOpen = true
            view.openLinked(request)
        }
    }

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

    /** The requests of the active document (parsed there, when its text changes). */
    val requests: List<RequestTemplate> get() = active?.requests ?: emptyList()
    val parseError: String? get() = active?.parseError

    /** Held for the whole of [run] and of a file switch, so the two never overlap and runs never overlap each other. */
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private val cancellation = RunCancellation()

    fun open(path: Path) = exclusive("open a file") { openLocked(path) }

    fun create(path: Path) = exclusive("create a file") {
        if (!Files.exists(path)) Files.writeString(path, "GET https://httpbin.org/get\n")
        openLocked(path)
    }

    private fun openLocked(path: Path, remember: Boolean = true) {
        val key = canonical(path)
        notice = null
        // A file that is already open is shown as it is (its unsaved edits and results are kept), not read again.
        val existing = documents.indexOfFirst { canonical(it.file) == key }
        if (existing >= 0) {
            activate(existing)
            return
        }
        val content = Files.readString(path)   // read first: a failure leaves the tabs untouched
        val doc = OpenDocument(path, content)
        synchronized(tabsLock) {
            documents = documents + doc
            activeIndex = documents.size - 1
        }
        if (remember) rememberFile(doc)
        if (historyOpen) historyView?.loadInBackground(path.toAbsolutePath())   // not on the caller's thread: it may be the UI thread
        refreshEnvironments(doc)
        // Restore the remembered environment only if it still exists; never an error for a vanished name.
        settings?.current?.environments?.get(key)?.takeIf { it in doc.environments }?.let { doc.chosenEnvironment = it }
    }

    /**
     * Reopens the tabs of the previous session, in order, and puts the one that was on screen back on screen. A file that is gone
     * or unreadable is skipped without a message: the app starts with what is left. Settings from before tabs existed
     * reopen the last file instead.
     */
    fun restoreTabs() {
        val saved = settings?.current ?: return
        val files = saved.openFiles ?: return restoreLastFile()
        exclusive("open a file") {
            val missing = mutableListOf<String>()
            for (f in files) {
                val path = Path.of(f)
                val opened = Files.isRegularFile(path) && try { openLocked(path, remember = false); true } catch (_: Exception) { documents.any { canonical(it.file) == canonical(path) } }
                if (!opened) missing += f
            }
            unavailable = missing
            saved.activeFile?.let { a -> documents.indexOfFirst { canonical(it.file) == a }.takeIf { it >= 0 }?.let { activate(it, restoring = true) } }
            rememberTabs()   // once, for the whole restore; the recents and the last file stay as the user left them
        }
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
        val doc = active ?: return
        doc.edit(newText)
    }

    /** Saves the tab on screen. Returns false (with [message] set) when the file could not be written; refused while a run is in progress. */
    fun save(): Boolean = saveDocument(active)

    private fun saveDocument(doc: OpenDocument?): Boolean {
        if (!busy.compareAndSet(false, true)) {
            message = "Cannot save while a request is running."
            return false
        }
        try {
            if (doc == null) return true
            if (!write(doc, doc.text)) return false
            message = null
            refreshQuietly(doc)
            return true
        } finally {
            busy.set(false)
        }
    }

    /** Writes [t] to [doc]'s file; its dirty flag is cleared only if the buffer still equals what was written. False (with [message]) on failure. */
    private fun write(doc: OpenDocument, t: String): Boolean = try {
        Files.writeString(doc.file, t)
        doc.saved(t)
        true
    } catch (e: Exception) {
        message = e.message ?: e::class.simpleName
        false
    }

    /** An environment-file error is reported but never counts as a failed write or blocks a run. */
    private fun refreshQuietly(doc: OpenDocument) {
        try { refreshEnvironments(doc) } catch (e: Exception) { message = e.message ?: e::class.simpleName }
    }

    /** Runs the selected request (with prerequisites), or all requests when [all]. Blocking. */
    fun run(all: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val doc = active ?: return
            val path = doc.file
            if (closing === doc) closing = null   // the run saves the file, so the question about its edits is moot
            cancellation.reset()
            notice = null
            running = true
            runningDocument = doc
            synchronized(lock) { doc.beginRun() }   // never leave the previous run's results next to a new error
            val t = doc.text   // one snapshot for the write and the run
            if (!write(doc, t)) return   // keep the save error visible instead of running stale text
            message = null
            refreshQuietly(doc)
            val index = doc.selected
            val requests = doc.requests
            if (!all && requests.isNotEmpty() && index !in requests.indices) {
                message = "No request is selected (the file has ${requests.size}); pick one from the list."
                return
            }
            val tracked = cancellation.track {
                runner.execute(path, t, if (all) null else index, doc.chosenEnvironment) { o -> synchronized(lock) { doc.addOutcome(o) } }
            }
            recordRun(doc)
            // Outcomes that finished before the cancel stay; the cancelled request is not remembered (see RunController).
            if (tracked is Tracked.Cancelled) doc.markCancelled()
        } catch (e: Exception) {
            message = e.message ?: e::class.simpleName
        } finally {
            running = false
            runningDocument = null
            busy.set(false)
        }
    }

    /** Stores the run in the history when recording is on; what finished before a cancel is stored too. A store failure only sets [message]. */
    private fun recordRun(doc: OpenDocument) {
        val store = history ?: return
        if (!recording) return
        val done = synchronized(lock) { doc.outcomes }
        if (done.isEmpty()) return
        val abs = doc.file.toAbsolutePath()
        try {
            store.record(abs, RunReport.from(done), done.map { (it as? RunOutcome.Success)?.response })
            synchronized(tabsLock) { if (historyOpen && active === doc) historyView?.load(abs, keepSelection = true) }   // not when another tab is on screen; checked under the lock so a switch cannot slip in between
        } catch (e: Exception) {
            message = "Could not record history: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Stops the running request; does nothing when none is running. The run ends shortly after with [cancelled] set. */
    fun cancel() {
        if (running) cancellation.cancel()
    }

    /** Forgets remembered responses, so the next run sends the chained requests again, and says so in [notice]. */
    fun resetChain() {
        runner.resetChain()
        notice = CHAIN_RESET_NOTICE
        noticeId++
    }

    fun clearNotice() {
        notice = null
    }

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

    private fun refreshEnvironments(doc: OpenDocument) {
        try {
            doc.updateEnvironments(Workspace.environmentNames(doc.file))
        } catch (e: Exception) {
            doc.updateEnvironments(emptyList())   // never keep another file's (or a now-broken) list
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

/** Shown after **Reset chain**; the plugin reports the same event. */
internal const val CHAIN_RESET_NOTICE = "Chain reset"

/**
 * The label of each open file: its name, with parent folders added (`dev/api.http`) for the files whose names collide,
 * until the labels differ or a path has no more folders.
 */
internal fun tabLabels(files: List<Path>): List<String> {
    val depth = IntArray(files.size)
    fun label(i: Int): String {
        val p = files[i]
        val last = p.nameCount - 1
        return (depth[i] downTo 0).map { k -> p.getName((last - k).coerceAtLeast(0)).toString() }.joinToString("/")
    }
    repeat(files.maxOfOrNull { it.nameCount } ?: 0) {
        val groups = files.indices.groupBy { label(it) }.values.filter { it.size > 1 }
        var changed = false
        for (g in groups) for (i in g) if (depth[i] + 1 < files[i].nameCount) { depth[i]++; changed = true }
        if (!changed) return@repeat
    }
    return files.indices.map { label(it) }
}
