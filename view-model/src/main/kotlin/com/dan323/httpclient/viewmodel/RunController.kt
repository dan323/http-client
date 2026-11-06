package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.UnknownEnvironmentException
import com.dan323.httpclient.core.application.resolve
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The request starting at or before 0-based [line]; null when [line] is above the first request. */
fun requestAt(requests: List<RequestTemplate>, line: Int): RequestTemplate? = requests.lastOrNull { it.startLine <= line }

/** Which request(s) of a file to run. */
sealed interface RunTarget {
    /** The request at the caret (see [requestAt]) and the requests it chains on. */
    data class AtLine(val line: Int) : RunTarget

    /** The request at this 0-based position in the file and the requests it chains on. */
    data class AtIndex(val index: Int) : RunTarget

    /** Every request, in file order. */
    data object All : RunTarget
}

sealed interface RunResult {
    /** [environment] is the name of the environment used, or null when none was chosen. */
    data class Completed(val environment: String?, val outcomes: List<RunOutcome>) : RunResult

    /** The run could not start; [message] is ready to show. [request] is the first request that would have run, if any. */
    data class Rejected(val reason: Reason, val message: String, val request: RequestTemplate? = null) : RunResult

    enum class Reason { PARSE, NO_REQUEST, ENVIRONMENT, FILE_ACCESS }
}

/**
 * The run logic every interactive front end shares: parses the text, picks the target request, resolves the
 * environment choice, and runs it with the requests it chains on in a session that is kept per
 * (file, environment, loaded variables). A later run of the same file and environment reuses the stored responses of
 * requests that already ran (so a login request is sent once); editing the environment file gives a fresh session
 * and the stale one is dropped (unless it is running). The request that is the target is always sent.
 *
 * [newSession] builds an empty session for a file, or fails with the message to show (e.g. a file-access
 * configuration error). Sessions it returns are cached here; the owner of the executor closes it.
 *
 * Blocking; interrupts and cancellation propagate as exceptions from the session, exactly as from
 * [RequestSession]. Safe to call from several threads (each session serialises its own runs).
 */
class RunController(
    private val locator: EnvironmentLocator,
    private val newSession: (Path) -> Result<RequestSession>,
) {
    private data class SessionKey(val file: Path, val environment: String?, val variables: Map<String, String>)

    private val sessions = ConcurrentHashMap<SessionKey, RequestSession>()
    private val running = ConcurrentHashMap<SessionKey, Int>()
    // Makes "evict obsolete, find or create, register as running" one step, so a concurrent run cannot evict a session
    // between another run's lookup and its registration.
    private val acquireLock = Any()

    /** Forgets remembered responses, so the next run sends the chained requests again. */
    fun resetChain() = sessions.clear()

    /** Number of cached sessions (for tests and diagnostics). */
    val cachedSessions: Int get() = sessions.size

    fun run(
        file: Path,
        text: String,
        target: RunTarget,
        choice: EnvironmentChoice,
        onEnvironment: (String?) -> Unit = {},
        onReused: (List<RequestTemplate>) -> Unit = {},
        onOutcome: (RunOutcome) -> Unit = {},
    ): RunResult {
        val requests = try { HttpFileParser().parse(text).requests } catch (e: HttpParseException) {
            return RunResult.Rejected(RunResult.Reason.PARSE, e.message ?: "Could not parse the file.")
        }
        val current: RequestTemplate? = when (target) {
            is RunTarget.AtLine -> requestAt(requests, target.line)
                ?: return RunResult.Rejected(RunResult.Reason.NO_REQUEST, "No request found at the caret.")
            is RunTarget.AtIndex -> requests.getOrNull(target.index)
                ?: return RunResult.Rejected(RunResult.Reason.NO_REQUEST, "No such request: ${target.index + 1}")
            RunTarget.All -> null
        }
        // "None" runs without an environment; a name that no longer exists is reported, never replaced by another.
        // The env file is only read when a named environment is chosen.
        val env = try { choice.resolve(locator, file) } catch (e: UnknownEnvironmentException) {
            return RunResult.Rejected(RunResult.Reason.ENVIRONMENT, e.message ?: "Unknown environment")
        } catch (e: EnvironmentFileException) {
            return RunResult.Rejected(RunResult.Reason.ENVIRONMENT, e.message ?: "Invalid environment file")
        }
        onEnvironment(env?.name)
        val variables = env?.variables ?: emptyMap()
        val key = SessionKey(file.toAbsolutePath(), env?.name, variables)
        val session = synchronized(acquireLock) {
            // Sessions for the same file+environment with other (older) variables are obsolete; drop them unless running.
            sessions.keys.filter { it.file == key.file && it.environment == key.environment && it != key && !running.containsKey(it) }
                .forEach { sessions.remove(it) }
            val found = sessions[key] ?: run {
                val created = newSession(file).getOrElse {
                    return RunResult.Rejected(RunResult.Reason.FILE_ACCESS, "File access configuration error: ${it.message}", current ?: requests.firstOrNull())
                }
                sessions.putIfAbsent(key, created) ?: created
            }
            running.merge(key, 1, Int::plus)
            found
        }
        try {
            val outcomes = if (current == null) session.runAll(requests, variables, onOutcome = onOutcome)
            else session.runChained(requests, current, variables, onReused, onOutcome)
            return RunResult.Completed(env?.name, outcomes)
        } finally {
            synchronized(acquireLock) { running.compute(key) { _, n -> if (n == null || n <= 1) null else n - 1 } }
        }
    }
}
