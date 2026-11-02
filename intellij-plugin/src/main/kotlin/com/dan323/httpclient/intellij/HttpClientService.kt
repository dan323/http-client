package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.prettyJson
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.RequestTemplate
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Per-project state: the selected environment and what the last run printed. */
@Service(Service.Level.PROJECT)
class HttpClientService(private val project: Project) {
    @Volatile var environmentName: String? = null
    @Volatile var lastOutput: String = "Run a request with Ctrl+Alt+Enter."
        private set
    private val listeners = mutableListOf<(String) -> Unit>()

    fun onOutput(listener: (String) -> Unit) { synchronized(listeners) { listeners += listener } }

    private fun publish(text: String) {
        lastOutput = text
        synchronized(listeners) { listeners.toList() }.forEach { it(text) }
    }

    fun environments(dir: Path): List<Environment> = JsonEnvironmentRepository(dir).load()

    /** Runs the request starting at or before [caretLine], with its chained prerequisites. Blocking. */
    fun run(file: Path, text: String, caretLine: Int) {
        val requests = HttpFileParser().parse(text).requests
        val current = requests.lastOrNull { it.startLine <= caretLine }
            ?: return publish("No request found at the caret.")
        val dir = file.toAbsolutePath().parent
        val variables = environmentName
            ?.let { name -> environments(dir).firstOrNull { it.name == name }?.variables }
            ?: emptyMap()

        val executor = JdkHttpExecutor()
        val session = RequestSession(RequestRunner(executor, LocalFileReader(dir)), executor, KotlinxJsonExtractor())
        val out = StringBuilder()
        session.runAll(withDependencies(requests, listOf(current)), variables) { outcome ->
            out.append("### ").append(describe(outcome.request)).append('\n')
            when (outcome) {
                is RunOutcome.Success -> {
                    val resp = outcome.response
                    out.append("HTTP ${resp.statusCode} (${resp.durationMillis} ms)\n")
                    resp.headers.forEach { out.append("${it.name}: ${it.value}\n") }
                    val body = resp.bodyAsString()
                    out.append('\n').append(prettyJson(body) ?: body).append('\n')
                }
                is RunOutcome.Failure -> out.append("Error: ${outcome.message}\n")
            }
            publish(out.toString())
        }
    }

    private fun describe(r: RequestTemplate) = r.name ?: "${r.method} ${r.url}"
}
