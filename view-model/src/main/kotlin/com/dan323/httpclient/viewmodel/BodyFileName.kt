package com.dan323.httpclient.viewmodel

private const val MAX_NAME = 100

/**
 * A file name to offer when saving a response body: the `filename` of `Content-Disposition` if there is one, else
 * the last segment of the URL path (unless it still holds a `{{variable}}`), else `response` plus an extension for the
 * content type. The result is at most 100 characters, never holds a path separator, a control character or a character
 * Windows rejects or reserves (`NUL.txt`), never starts with a dot and never ends with a dot or a space.
 */
fun suggestedFileName(url: String, contentDisposition: String?, contentType: String?): String {
    val fromHeader = contentDisposition?.let(::dispositionName)?.let(::sanitize)?.takeIf { it.isNotEmpty() }
    if (fromHeader != null) return fromHeader
    val stem = urlSegment(url)?.let(::sanitize)?.takeIf { it.isNotEmpty() } ?: "response"
    val extension = if (stem.contains('.')) "" else extensionFor(contentType)
    return stem.take(MAX_NAME - extension.length).trimEnd('.', ' ') + extension
}

/** Splits a header value at `;` outside double quotes. */
private fun splitParams(header: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    for (c in header) {
        when {
            escaped -> { current.append(c); escaped = false }
            c == '\\' && quoted -> { current.append(c); escaped = true }
            c == '"' -> { quoted = !quoted; current.append(c) }
            c == ';' && !quoted -> { out += current.toString(); current.clear() }
            else -> current.append(c)
        }
    }
    out += current.toString()
    return out.map { it.trim() }
}

private fun unquote(value: String): String {
    val v = value.trim()
    return if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") else v
}

private fun dispositionName(header: String): String? {
    val params = splitParams(header).drop(1)
    params.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { p ->
        val value = unquote(p.substringAfter('='))
        val parts = value.split('\'', limit = 3)   // charset'language'percent-encoded name (RFC 8187)
        val encoded = if (parts.size == 3) parts[2] else value
        runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return params.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.let { unquote(it.substringAfter('=')) }?.takeIf { it.isNotBlank() }
}

private fun urlSegment(url: String): String? {
    val path = url.substringBefore('#').substringBefore('?').substringAfter("://", url).substringAfter('/', "")
    val segment = path.trimEnd('/').substringAfterLast('/')
    if (segment.isBlank() || "{{" in segment) return null   // an unresolved variable is no name
    return runCatching { java.net.URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(segment)
}

private fun sanitize(name: String): String =
    name.filter { it >= ' ' && it != '\u007f' }
        .map { if (it in "/\\:<>\"|?*") '_' else it }.joinToString("")
        .trim().trimStart('.').take(MAX_NAME).trimEnd('.', ' ').let(::avoidReserved)

private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/** Windows reserves device names with or without an extension (`NUL.txt`); a leading underscore makes them ordinary. */
private fun avoidReserved(name: String): String = if (name.substringBefore('.').trim().uppercase() in RESERVED) "_$name" else name

private fun extensionFor(contentType: String?): String {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return ".bin"
    return when {
        type == "application/json" || type.endsWith("+json") -> ".json"
        type == "application/xml" || type == "text/xml" || type.endsWith("+xml") -> ".xml"
        type == "text/html" -> ".html"
        type == "text/csv" -> ".csv"
        type == "text/plain" -> ".txt"
        type == "image/png" -> ".png"
        type == "image/jpeg" -> ".jpg"
        type == "image/gif" -> ".gif"
        type == "application/pdf" -> ".pdf"
        else -> ".bin"
    }
}
