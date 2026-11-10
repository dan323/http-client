package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.Part
import com.dan323.httpclient.core.domain.PartContent

/** Splits the body lines of a `multipart/...` request into parts. See `docs/multipart-design.md`. */
internal object MultipartParser {
    private val boundaryParameter = Regex("""(?i);\s*boundary\s*=\s*(?:"([^"]+)"|([^;\s]+))""")

    /** The `boundary` parameter of a `Content-Type` value that names a `multipart/...` type, or null. */
    fun boundaryOf(contentType: String): String? {
        if (!contentType.trim().startsWith("multipart/", ignoreCase = true)) return null
        return boundaryParameter.find(contentType)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
    }

    fun isMultipart(contentType: String) = contentType.trim().startsWith("multipart/", ignoreCase = true)

    /** [firstLine] is the 1-based number of `lines[0]`, for error messages. */
    fun parse(lines: List<String>, firstLine: Int, boundary: String, warnings: MutableList<String>): Body.Multipart {
        val delimiter = "--$boundary"
        val closing = "$delimiter--"
        fun lineNo(k: Int) = firstLine + k
        val parts = mutableListOf<Part>()
        var current: MutableList<IndexedValue<String>>? = null
        var closed = false
        var start = 0
        fun finish(chunk: List<IndexedValue<String>>) { parts += toPart(chunk, lineNo(start), ::lineNo, warnings) }

        for ((k, line) in lines.withIndex()) {
            val t = line.trimEnd()
            when {
                closed -> if (t.isNotBlank()) throw HttpParseException(lineNo(k), "text after the closing '$closing'")
                t == closing -> { current?.let(::finish); current = null; closed = true }
                t == delimiter -> { current?.let(::finish); current = mutableListOf(); start = k }
                current != null -> current += IndexedValue(k, line)
                t.isNotBlank() -> throw HttpParseException(lineNo(k), "multipart body must start with '$delimiter'")
            }
        }
        if (!closed) throw HttpParseException(lineNo(lines.size - 1), "multipart body is missing the closing '$closing'")
        return Body.Multipart(boundary, parts)
    }

    private fun toPart(chunk: List<IndexedValue<String>>, delimiterLine: Int, lineNo: (Int) -> Int, warnings: MutableList<String>): Part {
        val blank = chunk.indexOfFirst { it.value.isBlank() }
        val headerLines = if (blank < 0) chunk else chunk.subList(0, blank)
        val content = if (blank < 0) emptyList() else chunk.subList(blank + 1, chunk.size)
        val headers = headerLines.map { (k, l) ->
            val idx = l.indexOf(':')
            if (idx <= 0) throw HttpParseException(lineNo(k), "part header line without ':' (expected 'Name: value'): ${l.trim()}")
            Header(l.substring(0, idx).trim(), l.substring(idx + 1).trim())
        }
        if (headers.none { it.name.equals("Content-Disposition", ignoreCase = true) }) {
            throw HttpParseException(delimiterLine, "multipart part has no Content-Disposition header")
        }
        val single = content.singleOrNull()?.takeIf { it.value.isNotBlank() }
        val text = single?.value?.trim()
        val body = when {
            text != null && text.startsWith("< ") -> PartContent.File(text.removePrefix("<").trim())
            else -> {
                if (text != null && text.startsWith("<@ ")) {
                    warnings += "Line ${lineNo(single.index)}: '<@' file parts are not supported; the line is sent as text (use '< path')"
                }
                PartContent.Text(content.joinToString("\n") { it.value })
            }
        }
        return Part(headers, body)
    }
}
