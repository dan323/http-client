package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.UpwardEnvironmentLocator
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.adapters.DefaultTimeout
import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.FileHistoryStore
import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.viewmodel.RunController
import com.dan323.httpclient.viewmodel.RunResult
import com.dan323.httpclient.viewmodel.RunTarget
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

    /** Environment names from the nearest enclosing `http-client.env.json` (same rule as the CLI and the plugin). */
    fun environmentNames(file: Path): List<String> =
        UpwardEnvironmentLocator().environments(file).map { it.name }
}

/**
 * Runs requests for the desktop app. Owns the HTTP executor (released by [close]) and keeps one session per
 * (file, environment, variables) in a [RunController], so a login request runs once and later sends reuse it.
 */
class DesktopRunner(
    private val policy: () -> Result<FileAccessPolicy> = { FileAccessPolicy.load() },
    private val defaultTimeout: () -> Result<Long> = { DefaultTimeout.load() },
) : AutoCloseable {
    // `>> path` saves work through the session; `<> path` links are kept in the file's .http-history, like the CLI and the plugin
    private val factory = SessionFactory(history = FileHistoryStore())
    private val controller = RunController(UpwardEnvironmentLocator()) { file -> policy().mapCatching { factory.create(file, it, defaultTimeoutMillis = defaultTimeout().getOrThrow()) } }

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() = controller.resetChain()

    /**
     * Runs the request at [index] in [text] (with the requests it chains on, unless they already ran for this file
     * and environment), or every request when [index] is null. Blocking; [onOutcome] is called as each request
     * completes. A parse or environment error is thrown as [IllegalStateException]; a bad file-access setting is
     * returned as a failed outcome.
     */
    fun execute(
        file: Path,
        text: String,
        index: Int?,
        environment: String?,
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> {
        // A name that no longer exists is rejected as an environment error, never runs another environment.
        val target = if (index == null) RunTarget.All else RunTarget.AtIndex(index)
        return when (val result = controller.run(file, text, target, EnvironmentChoice.of(environment), onOutcome = onOutcome)) {
            is RunResult.Completed -> result.outcomes
            is RunResult.Rejected -> when (result.reason) {
                RunResult.Reason.FILE_ACCESS -> {
                    val failure = RunOutcome.Failure(result.request ?: return emptyList(), result.message)
                    onOutcome(failure)
                    listOf(failure)
                }
                else -> throw IllegalStateException(result.message)
            }
        }
    }

    override fun close() = factory.close()
}
