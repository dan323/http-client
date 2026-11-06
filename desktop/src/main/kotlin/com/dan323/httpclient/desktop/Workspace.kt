package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.adapters.KotlinxJsonExtractor
import com.dan323.httpclient.adapters.LocalFileReader
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.RequestTemplate
import java.nio.file.Path

/** UI-independent operations of the desktop app. */
object Workspace {
    /** Lenient view for the editor's request list: a file that does not parse (e.g. mid-typing) lists nothing. */
    fun parse(text: String): List<RequestTemplate> =
        try { HttpFileParser().parse(text).requests } catch (e: HttpParseException) { emptyList() }

    /** The parse error for [text] (with its line number), or null when it parses; shown instead of a silently empty list. */
    fun parseError(text: String): String? =
        try { HttpFileParser().parse(text); null } catch (e: HttpParseException) { e.message }

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
        policy: () -> Result<FileAccessPolicy> = { FileAccessPolicy.load() },
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> {
        val dir = file.toAbsolutePath().parent
        val requests = HttpFileParser().parse(text).requests
        val selected = if (index == null) requests else withDependencies(requests, listOfNotNull(requests.getOrNull(index)))
        val variables = environment
            ?.let { name -> JsonEnvironmentRepository(dir).load().firstOrNull { it.name == name }?.variables }
            ?: emptyMap()
        val accessPolicy = policy().getOrElse { e ->
            val failure = RunOutcome.Failure(selected.firstOrNull() ?: return emptyList(), "File access configuration error: ${e.message}")
            onOutcome(failure)
            return listOf(failure)
        }
        val executor = JdkHttpExecutor()
        val session = RequestSession(RequestRunner(executor, LocalFileReader(dir, accessPolicy), DynamicVariables(env = SystemEnvironmentVariables.fromConfiguration())), executor, KotlinxJsonExtractor())
        return session.runAll(selected, variables, onOutcome = onOutcome)
    }
}
