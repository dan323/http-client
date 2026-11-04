package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.adapters.prettyJson
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.RequestTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
    @Volatile var environmentName: String? = null
    @Volatile var lastReport: Report = Report(message = "Run a request with Ctrl+Alt+Enter.")
        private set
    private val listeners = mutableListOf<(Report) -> Unit>()
    // One session per (file, environment): named responses survive between runs, so a login runs once.
    private val sessions = ConcurrentHashMap<Pair<Path, String?>, RequestSession>()

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

    /** Environments from the nearest directory (the file's, then its parents) holding an env file. */
    fun environments(dir: Path): List<Environment> = envDir(dir)?.let { JsonEnvironmentRepository(it).load() } ?: emptyList()

    private fun envDir(start: Path): Path? = generateSequence(start.toAbsolutePath()) { it.parent }.firstOrNull {
        Files.exists(it.resolve("http-client.env.json")) || Files.exists(it.resolve("http-client.private.env.json"))
    }

    /**
     * Runs the request starting at or before [caretLine]. Requests it chains on run first, unless they
     * already ran in this session (same file and environment): their stored response is reused.
     * The request at the caret is always sent. Blocking.
     */
    fun run(file: Path, text: String, caretLine: Int) {
        val requests = HttpFileParser().parse(text).requests
        val current = requests.lastOrNull { it.startLine <= caretLine }
            ?: return publish(Report(message = "No request found at the caret."))
        val dir = file.toAbsolutePath().parent
        val envs = try { environments(dir) } catch (e: Exception) {
            return publish(Report(message = "Could not read the environment file: ${e.message}"))
        }
        val env = envs.firstOrNull { it.name == environmentName } ?: envs.firstOrNull()
        val variables = env?.variables ?: emptyMap()

        val executor = JdkHttpExecutor()
        val session = sessions.getOrPut(file.toAbsolutePath() to env?.name) {
            RequestSession(RequestRunner(executor, LocalFileReader(dir), DynamicVariables(env = SystemEnvironmentVariables())), executor, KotlinxJsonExtractor())
        }
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
    }

    private fun describe(r: RequestTemplate) = r.name?.let { "$it  ·  ${r.method} ${r.url}" } ?: "${r.method} ${r.url}"
}
