package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.adapters.prettyJson
import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.UnknownEnvironmentException
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.application.resolve
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.RequestTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private data class SessionKey(val file: Path, val environment: String?, val variables: Map<String, String>)

/** One request's result as shown in the response window. [reused] means it was not re-sent. */
data class Section(
    val title: String,
    val reused: Boolean = false,
    val status: Int? = null,
    val millis: Long = 0,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = "",
    val error: String? = null,
)

data class Report(val message: String? = null, val environment: String? = null, val sections: List<Section> = emptyList())

/** Per-project state: the selected environment, remembered responses for chaining, and the last report. */
@Service(Service.Level.PROJECT)
class HttpClientService(private val project: Project) {
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
    // One session per (file, environment): named responses survive between runs, so a login runs once.
    // The key includes the loaded variables, so editing the env file starts a fresh session.
    private val sessions = ConcurrentHashMap<SessionKey, RequestSession>()
    private val running: MutableSet<SessionKey> = ConcurrentHashMap.newKeySet()

    /** Called whenever the environment choice is set, from any thread. */
    fun onEnvironmentChoice(listener: () -> Unit) { synchronized(choiceListeners) { choiceListeners += listener } }

    fun onReport(listener: (Report) -> Unit) { synchronized(listeners) { listeners += listener } }

    private fun publish(report: Report) {
        lastReport = report
        synchronized(listeners) { listeners.toList() }.forEach { it(report) }
    }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() {
        sessions.clear()
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
        val requests = try { HttpFileParser().parse(text).requests } catch (e: HttpParseException) {
            return publish(Report(message = e.message ?: "Could not parse the file."))
        }
        val current = requests.lastOrNull { it.startLine <= caretLine }
            ?: return publish(Report(message = "No request found at the caret."))
        val dir = file.toAbsolutePath().parent
        // "(none)" runs without an environment; a name that no longer exists is reported, never replaced by another.
        // The env file is only read when a named environment is chosen.
        val env = try { environmentChoice.resolve(locator, file) } catch (e: UnknownEnvironmentException) {
            return publish(Report(message = e.message))
        } catch (e: EnvironmentFileException) {
            return publish(Report(message = e.message))
        }
        val variables = env?.variables ?: emptyMap()

        val accessPolicy = FileAccessPolicy.load().getOrElse {
            return publish(Report(message = "File access configuration error: ${it.message}"))
        }
        val executor = JdkHttpExecutor()
        val key = SessionKey(file.toAbsolutePath(), env?.name, variables)
        // Sessions for the same file+environment with other (older) variables are obsolete; drop them unless running.
        sessions.keys.filter { it.file == key.file && it.environment == key.environment && it != key && it !in running }
            .forEach { sessions.remove(it) }
        val session = sessions.getOrPut(key) {
            RequestSession(RequestRunner(executor, LocalFileReader(dir, accessPolicy), DynamicVariables(env = SystemEnvironmentVariables.fromConfiguration())), executor, KotlinxJsonExtractor())
        }
        running.add(key)
        try {
        val sections = mutableListOf<Section>()
        val report = { Report(environment = env?.name ?: "none", sections = sections.toList()) }
        session.runChained(requests, current, variables,
            onReused = { reused ->
                reused.forEach { sections += Section(describe(it), reused = true) }
                publish(report())
            },
        ) { outcome ->
            sections += when (outcome) {
                is RunOutcome.Success -> {
                    val resp = outcome.response
                    val body = resp.bodyAsString()
                    Section(describe(outcome.request), status = resp.statusCode, millis = resp.durationMillis,
                        headers = resp.headers.map { it.name to it.value }, body = prettyJson(body) ?: body)
                }
                is RunOutcome.Failure -> Section(describe(outcome.request), error = outcome.message)
            }
            publish(report())
        }
        } finally { running.remove(key) }
    }

    private fun describe(r: RequestTemplate) = r.name?.let { "$it  ·  ${r.method} ${r.url}" } ?: "${r.method} ${r.url}"
}
