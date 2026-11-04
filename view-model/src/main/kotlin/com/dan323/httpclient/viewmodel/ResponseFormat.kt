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

/**
 * Human-readable size: "12 B", "1.5 KB", "2.0 MB", "2.0 GB". Units are promoted on the rounded value
 * (so 1048575 is "1.0 MB", never "1024.0 KB"); negative sizes are treated as 0.
 */
fun sizeLabel(bytes: Int): String {
    val b = maxOf(bytes, 0)
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB")
    var value = b / 1024.0
    var unit = 0
    while (unit < units.lastIndex && Math.round(value * 10) >= 10240) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

/**
 * Human-readable duration: "42 ms", "1.5 s", "2m 5s". Promotes on the rounded value (59999 ms is "1m 0s",
 * never "60.0 s"); negative durations (clock skew) are treated as 0.
 */
fun durationLabel(millis: Long): String {
    val ms = maxOf(millis, 0L)
    if (ms < 1000) return "$ms ms"
    val tenths = (ms + 50) / 100
    if (tenths < 600) return String.format(Locale.ROOT, "%.1f s", tenths / 10.0)
    val totalSeconds = (ms + 500) / 1000
    return "${totalSeconds / 60}m ${totalSeconds % 60}s"
}

/** Reason phrase for common status codes; empty when unknown. */
fun statusText(code: Int): String = when (code) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
    500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> ""
}
