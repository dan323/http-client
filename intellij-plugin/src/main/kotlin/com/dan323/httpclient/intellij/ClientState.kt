package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseLinks
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.RunCancellation
import com.dan323.httpclient.viewmodel.RunController
import com.dan323.httpclient.viewmodel.RunResult
import com.dan323.httpclient.viewmodel.RunTarget
import com.dan323.httpclient.viewmodel.Tracked
import com.dan323.httpclient.viewmodel.asRequest
import com.dan323.httpclient.viewmodel.requestAt
import com.dan323.httpclient.viewmodel.storedLabel
import com.dan323.httpclient.viewmodel.truncationNotice
import com.dan323.httpclient.viewmodel.cancelledLabel
import com.dan323.httpclient.viewmodel.display
import com.dan323.httpclient.viewmodel.reusedDisplay
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** One request's result as shown in the response window (reused, response or error). */
data class Section(val display: OutcomeDisplay)

/** Nothing to show yet (no message, environment or section): the tool window then shows its how-to-run hint. */
data class Report(val message: String? = null, val environment: String? = null, val sections: List<Section> = emptyList()) {
    val isEmpty: Boolean get() = message == null && environment == null && sections.isEmpty()
}

/**
 * The IDE-independent state behind [HttpClientService]: the environment choice, the last report and its listeners, the
 * session controller, and the "one run at a time" guard. No `Project` here, so it is testable headless.
 */
class ClientState(
    locator: EnvironmentLocator,
    maxSessions: Int = 16,
    private val history: HistoryStore? = null,
    newSession: (Path) -> Result<RequestSession>,
) {
    /** The user's explicit choice: [EnvironmentChoice.None] by default, never silently replaced by another environment. */
    @Volatile var environmentChoice: EnvironmentChoice = EnvironmentChoice.None
        set(value) {
            field = value
            synchronized(choiceListeners) { choiceListeners.toList() }.forEach { it() }
        }
    @Volatile var lastReport: Report = Report()
        private set
    private val choiceListeners = mutableListOf<() -> Unit>()
    private val listeners = mutableListOf<(Report) -> Unit>()
    private val controller = RunController(locator, maxSessions, newSession)
    private val running = AtomicBoolean(false)
    private val cancellation = RunCancellation()
    private val runListeners = mutableListOf<(Boolean) -> Unit>()
    // Makes "start (claim, forget an old cancel, tell listeners)", "end (release, tell listeners)" and "cancel" one step
    // each: a cancel cannot be erased by a starting run, and a finishing run's `false` cannot arrive after a newer `true`.
    private val runLock = Any()
    // Text last run per file, least recently used first, bounded like the sessions; the file evicted here also loses its
    // sessions (see [rememberText]), so the two caches never disagree about a file.
    private val lastTexts = object : LinkedHashMap<Path, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, String>): Boolean {
            val evict = size > maxSessions
            if (evict) evicted.add(eldest.key)
            return evict
        }
    }
    private val evicted = mutableListOf<Path>() // guarded by lastTexts
    internal val rememberedTexts: Int get() = synchronized(lastTexts) { lastTexts.size }

    val isRunning: Boolean get() = running.get()
    val cachedSessions: Int get() = controller.cachedSessions

    /** Called whenever the environment choice is set, from any thread. Returns the function that unregisters it. */
    fun onEnvironmentChoice(listener: () -> Unit): () -> Unit {
        synchronized(choiceListeners) { choiceListeners += listener }
        return { synchronized(choiceListeners) { choiceListeners -= listener } }
    }

    /** Called with `true` when a run starts and `false` when it ends (finished, failed or cancelled), from the running thread. */
    fun onRunning(listener: (Boolean) -> Unit): () -> Unit {
        synchronized(runListeners) { runListeners += listener }
        return { synchronized(runListeners) { runListeners -= listener } }
    }

    private fun notifyRunning(value: Boolean) = synchronized(runListeners) { runListeners.toList() }.forEach { it(value) }

    /** Claims the run guard and tells listeners; false when a run is in progress. Releases the guard if a listener throws. */
    private fun begin(): Boolean = synchronized(runLock) {
        if (!running.compareAndSet(false, true)) return false
        cancellation.reset()
        try {
            notifyRunning(true)
        } catch (e: Throwable) {
            running.set(false)
            throw e
        }
        true
    }

    private fun end() = synchronized(runLock) {
        running.set(false)
        notifyRunning(false)
    }

    /** Stops the running request, if any; its report then reads `Cancelled`. Safe from any thread. */
    fun cancel() {
        synchronized(runLock) { if (running.get()) cancellation.cancel() }
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

    /**
     * What sessions may read changed (the allowlists): drops every session, running ones included, so no later run uses
     * one made before the change. The running request finishes; its report stays.
     */
    fun accessChanged() {
        controller.invalidateSessions()
        synchronized(lastTexts) { lastTexts.clear() }
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
    fun run(file: Path, text: String, caretLine: Int): Boolean = runTarget(file, text, RunTarget.AtLine(caretLine))

    /**
     * Runs every request of [file] in file order, with the same guard, text check and report as [run]. A request that
     * fails does not stop the ones after it. Blocking. Returns false, doing nothing, when another run is in progress.
     */
    fun runAll(file: Path, text: String): Boolean = runTarget(file, text, RunTarget.All)

    /**
     * Shows the latest response recorded for the `<>` link of the request at [caretLine] (see [ResponseLinks]) as the
     * report, without sending anything. A request with no link, nothing recorded yet or a store that cannot be read is
     * reported in the message, never thrown. Blocking (disk). Returns false, doing nothing, while a run is in progress
     * (a run owns the report). A link is found by the request's name or start line, so pass the text as saved.
     */
    fun showLinked(file: Path, text: String, caretLine: Int): Boolean = synchronized(runLock) {
        if (running.get()) return false
        publish(linkedReport(file, text, caretLine))
        true
    }

    private fun linkedReport(file: Path, text: String, caretLine: Int): Report {
        val store = history ?: return Report(message = "No response history is available.")
        val request = try { requestAt(HttpFileParser().parse(text).requests, caretLine) } catch (e: HttpParseException) {
            return Report(message = e.message ?: "Could not parse the file.")
        } ?: return Report(message = "No request found at the caret.")
        if (request.responseLinks.isEmpty()) return Report(message = "This request has no <> link.")
        val stored = try { ResponseLinks.latest(store, file.toAbsolutePath(), request) } catch (e: Exception) {
            return Report(message = "Could not read the linked response: ${e.message ?: e::class.simpleName}")
        } ?: return Report(message = "No response is recorded for this request's <> link yet. Run it first.")
        val message = "Linked response: ${storedLabel(stored)}" + (truncationNotice(stored)?.let { "\n$it" } ?: "")
        return Report(message = message, sections = listOf(Section(RunOutcome.Success(stored.asRequest(), stored.response).display())))
    }

    private fun runTarget(file: Path, text: String, target: RunTarget): Boolean {
        if (!begin()) return false
        try {
            val abs = file.toAbsolutePath()
            val (previous, dropped) = synchronized(lastTexts) {
                val old = lastTexts.put(abs, text)
                old to evicted.toList().also { evicted.clear() }
            }
            dropped.forEach { controller.forget(it) }
            if (previous != null && previous != text) controller.forget(abs)
            val sections = mutableListOf<Section>()
            var envName: String? = null
            val report = { Report(environment = envName ?: "none", sections = sections.toList()) }
            val tracked = cancellation.track {
                controller.run(file, text, target, environmentChoice,
                    onEnvironment = { envName = it },
                    onReused = { reused ->
                        reused.forEach { sections += Section(reusedDisplay(it)) }
                        publish(report())
                    },
                ) { outcome ->
                    sections += Section(outcome.display())
                    publish(report())
                }
            }
            when (tracked) {
                // A cancelled run is an outcome, not an error: what finished stays, with a Cancelled line above it.
                Tracked.Cancelled -> publish(report().copy(message = cancelledLabel(sections.count { it.display !is OutcomeDisplay.Reused })))
                is Tracked.Done -> when (val result = tracked.value) {
                    is RunResult.Rejected -> publish(Report(message = result.message))
                    is RunResult.Completed -> if (sections.isEmpty()) publish(report().copy(message = "The file has no requests."))
                }
            }
            return true
        } finally {
            end()
        }
    }
}
