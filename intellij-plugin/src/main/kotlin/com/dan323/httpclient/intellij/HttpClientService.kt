package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.RunController
import com.dan323.httpclient.viewmodel.RunResult
import com.dan323.httpclient.viewmodel.RunTarget
import com.dan323.httpclient.viewmodel.display
import com.dan323.httpclient.viewmodel.reusedDisplay
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** One request's result as shown in the response window (reused, response or error). */
data class Section(val display: OutcomeDisplay)

data class Report(val message: String? = null, val environment: String? = null, val sections: List<Section> = emptyList())

/** Per-project state: the selected environment, remembered responses for chaining, and the last report. */
@Service(Service.Level.PROJECT)
class HttpClientService(private val project: Project) : Disposable {
    /** The user's explicit choice: [EnvironmentChoice.None] by default, never silently replaced by another environment. */
    @Volatile var environmentChoice: EnvironmentChoice = EnvironmentChoice.None
        set(value) {
            field = value
            synchronized(choiceListeners) { choiceListeners.toList() }.forEach { it() }
        }
    @Volatile var lastReport: Report = Report(message = "Run a request with Ctrl+Alt+Enter.")
        private set
    private val locator = UpwardEnvironmentLocator()
    private val choiceListeners = mutableListOf<() -> Unit>()
    private val listeners = mutableListOf<(Report) -> Unit>()
    // One session per (file, environment, variables) lives in the controller: named responses survive between
    // runs, so a login runs once. The factory owns the HTTP executor and is closed with the project.
    private val factory = SessionFactory()
    private val controller = RunController(locator) { file -> factory.createConfigured(file) }

    override fun dispose() = factory.close()

    /** Called whenever the environment choice is set, from any thread. */
    fun onEnvironmentChoice(listener: () -> Unit) { synchronized(choiceListeners) { choiceListeners += listener } }

    fun onReport(listener: (Report) -> Unit) { synchronized(listeners) { listeners += listener } }

    private fun publish(report: Report) {
        lastReport = report
        synchronized(listeners) { listeners.toList() }.forEach { it(report) }
    }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() {
        controller.resetChain()
        publish(lastReport.copy(message = "Chain reset: the next run sends the requests it depends on again."))
    }

    /** Environments for [file]: the nearest enclosing `http-client.env.json` (same rule as the CLI and desktop). */
    fun environments(file: Path): List<Environment> = locator.environments(file)

    /**
     * Runs the request starting at or before [caretLine]. Requests it chains on run first, unless they
     * already ran in this session (same file and environment): their stored response is reused.
     * The request at the caret is always sent. Blocking.
     */
    fun run(file: Path, text: String, caretLine: Int) {
        val sections = mutableListOf<Section>()
        var envName: String? = null
        val report = { Report(environment = envName ?: "none", sections = sections.toList()) }
        val result = controller.run(file, text, RunTarget.AtLine(caretLine), environmentChoice,
            onEnvironment = { envName = it },
            onReused = { reused ->
                reused.forEach { sections += Section(reusedDisplay(it)) }
                publish(report())
            },
        ) { outcome ->
            sections += Section(outcome.display())
            publish(report())
        }
        if (result is RunResult.Rejected) publish(Report(message = result.message))
    }
}
