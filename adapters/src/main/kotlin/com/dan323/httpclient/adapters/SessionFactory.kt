package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RequestTimeouts
import com.dan323.httpclient.core.port.out.Allowlists
import com.dan323.httpclient.core.port.out.HistoryStore
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.nio.file.Path

/**
 * The composition root for running requests: assembles executor, runner, dynamic variables, file reader (confined by a
 * [FileAccessPolicy]), JSON extractor and [RequestSession], so every front end wires them the same way.
 *
 * It owns one [JdkHttpExecutor], shared by all sessions it creates, and releases it in [close]. Close it when the
 * front end shuts down (sessions created from it must not be used afterwards).
 */
class SessionFactory(
    private val executor: HttpExecutor = JdkHttpExecutor(),
    /**
     * Where the latest response of requests with a `<> path` link is kept (opt-in: null keeps no links; use
     * `FileHistoryStore()` or one with an injected root). Requests without `<>` never touch it.
     */
    private val history: HistoryStore? = null,
) : AutoCloseable {
    /**
     * A new, empty session for requests of [httpFile]: `< path` bodies are read, and `>> path` responses are written, in the file's folder plus the roots
     * of [policy]; `{{$env.NAME}}` reads the OS variables allowed by `-Dhttpclient.allowEnv` / `HTTPC_ALLOW_ENV` plus
     * [allowEnv] (the hint in the error message is [envHint]). A request without `# @timeout` gets [defaultTimeoutMillis]
     * as its total timeout (headers and body). [maxTimeoutMillis] caps every request's total timeout, `# @timeout` included, for the whole redirect chain. With [ambientEnvironment] false, only [allowEnv] is readable (`-Dhttpclient.allowEnv` and `HTTPC_ALLOW_ENV` are ignored: for callers whose user must grant each name). With [refuseEnvironmentFiles], `< path` never reads `http-client*.env.json`. With [persistence] false, `>>`, `>>!` and `<>` fail (nothing is written; for callers that must not write).
     */
    fun create(
        httpFile: Path,
        policy: FileAccessPolicy,
        allowEnv: Set<String> = emptySet(),
        envHint: String = SystemEnvironmentVariables.DEFAULT_HINT,
        defaultTimeoutMillis: Long = RequestTimeouts.DEFAULT_MILLIS,
        persistence: Boolean = true,
        maxTimeoutMillis: Long? = null,
        refuseEnvironmentFiles: Boolean = false,
        ambientEnvironment: Boolean = true,
    ): RequestSession {
        val dir = httpFile.toAbsolutePath().parent
        val env = if (ambientEnvironment) SystemEnvironmentVariables.fromConfiguration(extra = allowEnv, hint = envHint)
        else SystemEnvironmentVariables(allowEnv, envHint)
        val dynamic = DynamicVariables(env = env)
        val session = RequestSession(
            RequestRunner(executor, LocalFileReader(dir, policy, refuseEnvironmentFiles), dynamic, defaultTimeoutMillis, maxTimeoutMillis), executor, KotlinxJsonExtractor(),
        )
        return if (persistence) {
            session.withPersistence(LocalResponseSaver(httpFile, policy), history?.let { HistoryLinkRecorder(httpFile.toAbsolutePath(), it) })
        } else session
    }

    /**
     * Like [create] with the policy and the default timeout from the system properties / environment variables
     * ([FileAccessPolicy.load], [DefaultTimeout.load]), plus what the user allowed in the front end's settings
     * ([allowlists]); [fileHint] and [envHint] say where to allow more. A bad setting comes back as a failure carrying
     * the message for the user.
     */
    fun createConfigured(
        httpFile: Path,
        allowlists: Allowlists = Allowlists(),
        fileHint: String = FileAccessPolicy.DEFAULT_HINT,
        envHint: String = SystemEnvironmentVariables.DEFAULT_HINT,
    ): Result<RequestSession> =
        FileAccessPolicy.load(settingsRoots = allowlists.fileRoots, hint = fileHint).mapCatching { policy ->
            create(
                httpFile, policy, allowEnv = allowlists.envNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet(), envHint = envHint,
                defaultTimeoutMillis = DefaultTimeout.load().getOrThrow(),
            )
        }

    override fun close() { (executor as? AutoCloseable)?.close() }
}
