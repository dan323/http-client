package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.FileHistoryStore
import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.domain.Environment
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Path

/**
 * Per-project service (registered by the annotation only, not in plugin.xml): wraps [ClientState] with the
 * IDE-bound lifecycle. The [SessionFactory] owns the HTTP executor, shared by all sessions, and is closed with the project.
 */
@Service(Service.Level.PROJECT)
@State(name = "HttpClientEnvironment", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HttpClientService(private val project: Project) : Disposable, PersistentStateComponent<HttpClientService.Saved> {
    /** What survives an IDE restart (workspace file, so it is not shared through version control): the chosen environment name, null for none. */
    class Saved { var environment: String? = null }

    private val locator = UpwardEnvironmentLocator()
    private val history = FileHistoryStore()
    private val factory = SessionFactory(history = history)
    private val allowlists = AllowlistSettings.getInstance()
    private val clientState = ClientState(locator, history = history) { file ->
        factory.createConfigured(file, allowlists.allowlists, AllowlistSettings.FILE_HINT, AllowlistSettings.ENV_HINT)
    }

    var environmentChoice: EnvironmentChoice
        get() = clientState.environmentChoice
        set(value) { clientState.environmentChoice = value }
    val lastReport: Report get() = clientState.lastReport
    val isRunning: Boolean get() = clientState.isRunning

    override fun getState() = Saved().also { it.environment = (clientState.environmentChoice as? EnvironmentChoice.Named)?.name }

    // A name that no longer exists is kept and reported at the next run (never replaced), like any other choice.
    override fun loadState(saved: Saved) { clientState.environmentChoice = EnvironmentChoice.of(saved.environment) }

    init {
        // Drop what is remembered about a file when it is deleted or moved/renamed (closing an editor keeps it: a
        // reopened file is still the same text, and the cache is bounded anyway).
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                events.filter { it is VFileDeleteEvent || it is VFileMoveEvent }
                    .forEach { e -> e.file?.let { f -> if (f.extension == "http") clientState.forget(Path.of(f.path)) } }
            }
        })
    }

    init {
        // Sessions were made with the old allowlists: the next run creates new ones (and sends the chained requests again).
        allowlists.onChange(this) { clientState.resetChain() }
    }

    init {
        // The underlines depend on the chosen environment: analyse the open files again when it changes.
        val remove = clientState.onEnvironmentChoice {
            ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart() }, project.disposed)
        }
        Disposer.register(this, Disposable { remove() })
    }

    override fun dispose() = factory.close()

    /** Called whenever the environment choice is set; unregistered when [parent] is disposed. */
    fun onEnvironmentChoice(parent: Disposable, listener: () -> Unit) = clientState.onEnvironmentChoice(listener).let { remove -> Disposer.register(parent, Disposable { remove() }) }

    /** Called with every new report; unregistered when [parent] is disposed. */
    fun onReport(parent: Disposable, listener: (Report) -> Unit) = clientState.onReport(listener).let { remove -> Disposer.register(parent, Disposable { remove() }) }

    /** Called with `true` when a request starts and `false` when it ends; unregistered when [parent] is disposed. */
    fun onRunning(parent: Disposable, listener: (Boolean) -> Unit) = clientState.onRunning(listener).let { remove -> Disposer.register(parent, Disposable { remove() }) }

    fun resetChain() = clientState.resetChain()

    /** Stops the running request, if any (see [ClientState.cancel]). */
    fun cancel() = clientState.cancel()

    /** Environments for [file]: the nearest enclosing `http-client.env.json` (same rule as the CLI and desktop). */
    fun environments(file: Path): List<Environment> = locator.environments(file)

    /**
     * The variable names (never the values) of each environment of [file]'s env file, for editor intelligence. Reads the
     * file: not on the UI thread. An unreadable file throws [com.dan323.httpclient.core.port.out.EnvironmentFileException]
     * (its message names the file and the position, never a value); no environments is an empty map.
     */
    fun environmentVariableNames(file: Path): Map<String, Set<String>> =
        environments(file).associate { it.name to it.variables.keys.toSet() }   // a copy: `keys` is a view that would keep the values (secrets) reachable

    /** See [ClientState.run]. Returns false when another request is still running. Blocking. */
    fun run(file: Path, text: String, caretLine: Int): Boolean = clientState.run(file, text, caretLine)

    /** See [ClientState.showLinked]. Returns false when a request is running. Blocking (disk). */
    fun showLinked(file: Path, text: String, caretLine: Int): Boolean = clientState.showLinked(file, text, caretLine)

    /** See [ClientState.runAll]. Returns false when another request is still running. Blocking. */
    fun runAll(file: Path, text: String): Boolean = clientState.runAll(file, text)
}
