package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import java.nio.file.Path

/** The user's environment choice, explicit so that "none" and "a name that vanished" are never confused. */
sealed interface EnvironmentChoice {
    /** No environment: variables come from the .http file only. */
    data object None : EnvironmentChoice

    /** The environment called [name]; it is an error if it does not exist. */
    data class Named(val name: String) : EnvironmentChoice

    companion object {
        fun of(name: String?): EnvironmentChoice = if (name == null) None else Named(name)
    }
}

/** The chosen name does not exist in the environments found for the file. */
class UnknownEnvironmentException(message: String) : RuntimeException(message)

/**
 * The environment [this] choice selects among [available]; null for [EnvironmentChoice.None]. Never falls back
 * to another environment: an unknown name throws [UnknownEnvironmentException].
 */
fun EnvironmentChoice.select(available: List<Environment>): Environment? = when (this) {
    EnvironmentChoice.None -> null
    is EnvironmentChoice.Named -> available.firstOrNull { it.name == name }
        ?: throw UnknownEnvironmentException(
            "Unknown environment: $name" +
                if (available.isEmpty()) " (no http-client.env.json found)" else " (available: ${available.joinToString { it.name }})"
        )
}

/** Environments for [httpFile] according to the locator's rule; empty when there is no env file. */
fun EnvironmentLocator.environments(httpFile: Path): List<Environment> = find(httpFile)?.load().orEmpty()
