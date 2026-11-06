package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.domain.Environment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Path

/**
 * Per-project service (registered by the annotation only, not in plugin.xml): wraps [ClientState] with the
 * IDE-bound lifecycle. The [SessionFactory] owns the HTTP executor, shared by all sessions, and is closed with the project.
 */
@Service(Service.Level.PROJECT)
class HttpClientService(@Suppress("unused") private val project: Project) : Disposable {
    private val locator = UpwardEnvironmentLocator()
    private val factory = SessionFactory()
    private val state = ClientState(locator) { file -> factory.createConfigured(file) }

    var environmentChoice: EnvironmentChoice
        get() = state.environmentChoice
        set(value) { state.environmentChoice = value }
    val lastReport: Report get() = state.lastReport

    override fun dispose() = factory.close()

    /** Called whenever the environment choice is set; unregistered when [parent] is disposed. */
    fun onEnvironmentChoice(parent: Disposable, listener: () -> Unit) = state.onEnvironmentChoice(listener).let { remove -> Disposer.register(parent, Disposable { remove() }) }

    /** Called with every new report; unregistered when [parent] is disposed. */
    fun onReport(parent: Disposable, listener: (Report) -> Unit) = state.onReport(listener).let { remove -> Disposer.register(parent, Disposable { remove() }) }

    fun resetChain() = state.resetChain()

    /** Environments for [file]: the nearest enclosing `http-client.env.json` (same rule as the CLI and desktop). */
    fun environments(file: Path): List<Environment> = locator.environments(file)

    /** See [ClientState.run]. Returns false when another request is still running. Blocking. */
    fun run(file: Path, text: String, caretLine: Int): Boolean = state.run(file, text, caretLine)
}
