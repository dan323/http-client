package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpFile
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.`in`.ParseHttpFile

/**
 * Parser for the JetBrains `.http` format: requests separated by `###`,
 * `# @name x` request names, `//` and `#` comments, `< path` file bodies.
 */
class HttpFileParser : ParseHttpFile {
    private val requestLine = Regex("""^([A-Za-z]+)\s+(\S.*?)(?:\s+HTTP/\S+)?$""")
    private val nameTag = Regex("""^(?:#|//)\s*@name\s+(\S+)""")
    private val knownMethods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")

    override fun parse(text: String): HttpFile {
        val blocks = mutableListOf<MutableList<String>>(mutableListOf())
        for (line in text.lines()) {
            if (line.startsWith("###")) blocks += mutableListOf<String>() else blocks.last() += line
        }
        return HttpFile(blocks.mapNotNull(::parseBlock))
    }

    private fun parseBlock(lines: List<String>): RequestTemplate? {
        var name: String? = null
        var i = 0
        // Leading comments / blanks, picking up @name.
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty()) { i++; continue }
            if (l.startsWith("#") || l.startsWith("//")) {
                nameTag.find(l)?.let { name = it.groupValues[1] }
                i++; continue
            }
            break
        }
        if (i >= lines.size) return null

        val first = lines[i].trim()
        val match = requestLine.matchEntire(first)
        // A bare URL means GET, as in IntelliJ.
        val (method, url) = if (match != null && match.groupValues[1].uppercase() in knownMethods) {
            match.groupValues[1].uppercase() to match.groupValues[2]
        } else {
            "GET" to first.substringBefore(" HTTP/")
        }
        i++

        val headers = mutableListOf<Header>()
        while (i < lines.size && lines[i].isNotBlank()) {
            val l = lines[i].trim()
            if (!l.startsWith("#") && !l.startsWith("//")) {
                val idx = l.indexOf(':')
                if (idx > 0) headers += Header(l.substring(0, idx).trim(), l.substring(idx + 1).trim())
            }
            i++
        }

        val bodyLines = lines.drop(i + 1).dropLastWhile { it.isBlank() }
        val body = when {
            bodyLines.isEmpty() -> null
            bodyLines.first().trim().startsWith("< ") && bodyLines.size == 1 ->
                Body.FromFile(bodyLines.first().trim().removePrefix("<").trim())
            else -> Body.Text(bodyLines.joinToString("\n"))
        }
        return RequestTemplate(name, method, url, headers, body)
    }
}
