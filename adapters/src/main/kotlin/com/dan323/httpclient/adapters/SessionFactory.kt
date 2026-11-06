package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.DynamicVariables
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import java.nio.file.Path

/**
 * The composition root for running requests: assembles executor, runner, dynamic variables, file reader (confined by a
 * [FileAccessPolicy]), JSON extractor and [RequestSession], so every front end wires them the same way.
 *
 * It owns one [JdkHttpExecutor], shared by all sessions it creates, and releases it in [close]. Close it when the
 * front end shuts down (sessions created from it must not be used afterwards).
 */
class SessionFactory(private val executor: JdkHttpExecutor = JdkHttpExecutor()) : AutoCloseable {
    /**
     * A new, empty session for requests of [httpFile]: `< path` bodies are read from the file's folder plus the roots
     * of [policy]; `{{$env.NAME}}` reads the OS variables allowed by `-Dhttpclient.allowEnv` / `HTTPC_ALLOW_ENV` plus
     * [allowEnv] (the hint in the error message is [envHint]).
     */
    fun create(
        httpFile: Path,
        policy: FileAccessPolicy,
        allowEnv: Set<String> = emptySet(),
        envHint: String = SystemEnvironmentVariables.DEFAULT_HINT,
    ): RequestSession {
        val dir = httpFile.toAbsolutePath().parent
        val dynamic = DynamicVariables(env = SystemEnvironmentVariables.fromConfiguration(extra = allowEnv, hint = envHint))
        return RequestSession(RequestRunner(executor, LocalFileReader(dir, policy), dynamic), executor, KotlinxJsonExtractor())
    }

    /**
     * Like [create] with the policy from the system property / environment variable ([FileAccessPolicy.load]); a bad
     * setting comes back as a failure carrying the message for the user.
     */
    fun createConfigured(httpFile: Path): Result<RequestSession> =
        FileAccessPolicy.load().map { create(httpFile, it) }

    override fun close() = executor.close()
}
