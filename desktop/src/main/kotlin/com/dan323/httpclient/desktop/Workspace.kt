package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Path

/** UI-independent operations of the desktop app. */
object Workspace {
    fun parse(text: String): List<RequestTemplate> = HttpFileParser().parse(text).requests

    /** Environment names from `http-client.env.json` next to [file]. */
    fun environmentNames(file: Path): List<String> =
        JsonEnvironmentRepository(file.toAbsolutePath().parent).load().map { it.name }

    /**
     * Runs the request at [index] in [text] (with the requests it chains on), or every request when
     * [index] is null. Blocking; [onOutcome] is called as each request completes.
     */
    fun execute(
        file: Path,
        text: String,
        index: Int?,
        environment: String?,
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> {
        val dir = file.toAbsolutePath().parent
        val requests = parse(text)
        val selected = if (index == null) requests else withDependencies(requests, listOfNotNull(requests.getOrNull(index)))
        val variables = environment
            ?.let { name -> JsonEnvironmentRepository(dir).load().firstOrNull { it.name == name }?.variables }
            ?: emptyMap()
        val executor = JdkHttpExecutor()
        val session = RequestSession(RequestRunner(executor, LocalFileReader(dir), DynamicVariables(env = SystemEnvironmentVariables())), executor, KotlinxJsonExtractor())
        return session.runAll(selected, variables, onOutcome = onOutcome)
    }
}
