package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.FileReader
import java.nio.file.Path

/** Resolves `< path` bodies relative to the directory of the .http file. */
class LocalFileReader(private val baseDir: Path) : FileReader {
    override fun read(path: String): ByteArray = baseDir.resolve(path).normalize().toFile().readBytes()
}
