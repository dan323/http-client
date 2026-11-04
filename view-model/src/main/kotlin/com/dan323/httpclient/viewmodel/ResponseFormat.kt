package com.dan323.httpclient.viewmodel

import java.util.Locale

/** Coarse class of an HTTP status code, used to pick a colour in any UI. */
enum class StatusClass { INFORMATIONAL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN }

fun statusClass(code: Int): StatusClass = when (code) {
    in 100..199 -> StatusClass.INFORMATIONAL
    in 200..299 -> StatusClass.SUCCESS
    in 300..399 -> StatusClass.REDIRECT
    in 400..499 -> StatusClass.CLIENT_ERROR
    in 500..599 -> StatusClass.SERVER_ERROR
    else -> StatusClass.UNKNOWN
}

/** Human-readable size: "12 B", "1.5 KB", "2.0 MB". */
fun sizeLabel(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** Human-readable duration: "42 ms", "1.5 s". */
fun durationLabel(millis: Long): String =
    if (millis < 1000) "$millis ms" else String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

/** Reason phrase for common status codes; empty when unknown. */
fun statusText(code: Int): String = when (code) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
    500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> ""
}
