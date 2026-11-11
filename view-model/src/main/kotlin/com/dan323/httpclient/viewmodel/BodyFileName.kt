package com.dan323.httpclient.viewmodel

private const val MAX_NAME = 100

/**
 * A file name to offer when saving a response body: the `filename` of `Content-Disposition` if there is one, else
 * the last segment of the URL path, else `response` plus an extension for the content type. The result never holds a
 * path separator or a control character and never starts with a dot.
 */
fun suggestedFileName(url: String, contentDisposition: String?, contentType: String?): String {
    val fromHeader = contentDisposition?.let(::dispositionName)
    val fromUrl = urlSegment(url)
    val extension = extensionFor(contentType)
    val raw = fromHeader ?: fromUrl ?: "response"
    val clean = sanitize(raw).ifEmpty { "response" }
    return if (fromHeader == null && !clean.contains('.')) clean + extension else clean
}

private fun dispositionName(header: String): String? {
    val params = header.split(';').drop(1).map { it.trim() }
    params.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { p ->
        val value = p.substringAfter('=').trim().trim('"')
        val encoded = value.substringAfter("''", value)
        runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return params.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
}

private fun urlSegment(url: String): String? {
    val path = url.substringBefore('#').substringBefore('?').substringAfter("://", url).substringAfter('/', "")
    return path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(it) }
}

private fun sanitize(name: String): String =
    name.filter { it >= ' ' && it != '\u007f' }
        .replace('/', '_').replace('\\', '_').replace(':', '_')
        .trim().trimStart('.').take(MAX_NAME)

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
