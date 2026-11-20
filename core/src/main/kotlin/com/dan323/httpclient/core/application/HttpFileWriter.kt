package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.PartContent
import com.dan323.httpclient.core.domain.RequestTemplate

/** Writes [RequestTemplate]s as the text of a `.http` file (what [HttpFileParser] reads back). Used by the Bruno conversion. */
object HttpFileWriter {
    /** [header] lines (without `#`) go at the top as comments; requests are separated by `###`. */
    fun write(requests: List<RequestTemplate>, header: List<String> = emptyList()): String = buildString {
        header.forEach { append("# ").append(it).append('\n') }
        val written = HashMap<String, String>()
        requests.forEachIndexed { i, r ->
            if (i > 0 || header.isNotEmpty()) append("\n###\n\n")
            // a file variable holds from its line on, so a line is only needed where the value changes
            r.fileVariables.forEach { (k, v) -> if (written[k] != v) { append('@').append(k).append(" = ").append(v).append('\n'); written[k] = v } }
            r.name?.let { append("# @name ").append(it).append('\n') }
            r.assertions.forEach { append("# @assert ").append(it).append('\n') }
            append(r.method).append(' ').append(r.url).append('\n')
            r.headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
            when (val body = r.body) {
                null -> {}
                is Body.Text -> append('\n').append(body.content).append('\n')
                is Body.FromFile -> append("\n< ").append(body.path).append('\n')
                is Body.Multipart -> {
                    append('\n')
                    body.parts.forEach { part ->
                        append("--").append(body.boundary).append('\n')
                        part.headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
                        append('\n')
                        when (val c = part.content) {
                            is PartContent.Text -> append(c.content).append('\n')
                            is PartContent.File -> append("< ").append(c.path).append('\n')
                        }
                    }
                    append("--").append(body.boundary).append("--\n")
                }
            }
        }
    }
}
