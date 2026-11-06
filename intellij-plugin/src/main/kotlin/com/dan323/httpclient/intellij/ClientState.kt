package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.RunController
import com.dan323.httpclient.viewmodel.RunResult
import com.dan323.httpclient.viewmodel.RunTarget
import com.dan323.httpclient.viewmodel.display
import com.dan323.httpclient.viewmodel.reusedDisplay
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** One request's result as shown in the response window (reused, response or error). */
data class Section(val display: OutcomeDisplay)

data class Report(val message: String? = null, val environment: String? = null, val sections: List<Section> = emptyList())

/**
 * The IDE-independent state behind [HttpClientService]: the environment choice, the last report and its listeners, the
 * session controller, and the "one run at a time" guard. No `Project` here, so it is testable headless.
 */
class ClientState(locator: EnvironmentLocator, maxSessions: Int = 16, newSession: (Path) -> Result<RequestSession>) {
    /** The user's explicit choice: [EnvironmentChoice.None] by default, never silently replaced by another environment. */
    @Volatile var environmentChoice: EnvironmentChoice = EnvironmentChoice.None
        set(value) {
            field = value
            synchronized(choiceListeners) { choiceListeners.toList() }.forEach { it() }
        }
    @Volatile var lastReport: Report = Report(message = "Run a request with Ctrl+Alt+Enter.")
        private set
    private val choiceListeners = mutableListOf<() -> Unit>()
    private val listeners = mutableListOf<(Report) -> Unit>()
    private val controller = RunController(locator, maxSessions, newSession)
    private val running = AtomicBoolean(false)
    private val lastTexts = HashMap<Path, String>()

    val isRunning: Boolean get() = running.get()
    val cachedSessions: Int get() = controller.cachedSessions

    /** Called whenever the environment choice is set, from any thread. Returns the function that unregisters it. */
    fun onEnvironmentChoice(listener: () -> Unit): () -> Unit {
        synchronized(choiceListeners) { choiceListeners += listener }
        return { synchronized(choiceListeners) { choiceListeners -= listener } }
    }

    /** Called with every new report, from the running thread. Returns the function that unregisters it. */
    fun onReport(listener: (Report) -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }

    private fun publish(report: Report) {
        lastReport = report
        synchronized(listeners) { listeners.toList() }.forEach { it(report) }
    }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() {
        controller.resetChain()
        synchronized(lastTexts) { lastTexts.clear() }
        // A running request owns the report; do not overwrite it.
        if (!running.get()) publish(lastReport.copy(message = "Chain reset: the next run sends the requests it depends on again."))
    }

    /** Drops everything remembered about [file] (it was deleted or closed). */
    fun forget(file: Path) {
        controller.forget(file)
        synchronized(lastTexts) { lastTexts.remove(file.toAbsolutePath()) }
    }

    /**
     * Runs the request starting at or before [caretLine] and the requests it chains on (stored responses of the same
     * file and environment are reused). Blocking. Returns false, doing nothing, when another run is in progress:
     * runs share a non-thread-safe session and one report. When [text] differs from the text last run for [file],
     * its remembered responses are dropped first (the requests they came from may have changed).
     */
    fun run(file: Path, text: String, caretLine: Int): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            val abs = file.toAbsolutePath()
            val changed = synchronized(lastTexts) { lastTexts.put(abs, text) }.let { it != null && it != text }
            if (changed) controller.forget(abs)
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
            return true
        } finally {
            running.set(false)
        }
    }
}
