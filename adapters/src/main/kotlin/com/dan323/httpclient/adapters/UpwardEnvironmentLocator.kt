package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import java.nio.file.Files
import java.nio.file.Path

/** Nearest enclosing directory (searching upward from the file's own) containing `http-client.env.json`. */
class UpwardEnvironmentLocator : EnvironmentLocator {
    override fun find(httpFile: Path): EnvironmentRepository? {
        val dir = generateSequence(httpFile.toAbsolutePath().normalize().parent) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve(JsonEnvironmentRepository.SHARED_FILE)) }
        return dir?.let { JsonEnvironmentRepository(it) }
    }
}
