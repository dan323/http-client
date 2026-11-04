package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A small in-memory cookie store (RFC 6265 subset) owned by one [RequestSession], so cookies are never
 * shared between sessions. Supports Domain (host-only when absent), Path, Max-Age, Expires and Secure;
 * there is no public-suffix list. Cookies set on intermediate redirect responses are not seen, because the
 * executor follows redirects itself.
 */
class CookieJar(private val clock: () -> Instant = Instant::now) {
    private data class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val hostOnly: Boolean,
        val path: String,
        val secure: Boolean,
        val expires: Instant?,
    ) {
        val key get() = Triple(name, domain, path)
    }

    private val cookies = LinkedHashMap<Triple<String, String, String>, Cookie>()

    /** Stores the `Set-Cookie` headers of a response received from [url]. Malformed ones are ignored. */
    @Synchronized
    fun store(url: String, headers: List<Header>) {
        val uri = parse(url) ?: return
        val host = uri.host?.lowercase() ?: return
        headers.filter { it.name.equals("Set-Cookie", ignoreCase = true) }.forEach { store(host, uri.path, it.value) }
    }

    private fun store(host: String, requestPath: String?, raw: String) {
        val parts = raw.split(';')
        val pair = parts[0]
        val eq = pair.indexOf('=')
        if (eq <= 0) return
        val name = pair.substring(0, eq).trim()
        if (name.isEmpty()) return
        val value = pair.substring(eq + 1).trim()
        var domain: String? = null
        var path: String? = null
        var secure = false
        var maxAge: Long? = null
        var expires: Instant? = null
        for (attr in parts.drop(1)) {
            val k = attr.substringBefore('=').trim().lowercase()
            val v = attr.substringAfter('=', "").trim()
            when (k) {
                "domain" -> domain = v.trimStart('.').lowercase().ifEmpty { null }
                "path" -> path = v.takeIf { it.startsWith("/") }
                "secure" -> secure = true
                "max-age" -> maxAge = v.toLongOrNull()
                "expires" -> expires = try {
                    ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (domain != null && !domainMatches(host, domain)) return // a host may not set cookies for other domains
        val effectivePath = path ?: defaultPath(requestPath)
        val now = clock()
        val expiry = maxAge?.let { now.plusSeconds(it.coerceAtMost(ONE_HUNDRED_YEARS)) } ?: expires
        val cookie = Cookie(name, value, domain ?: host, domain == null, effectivePath, secure, expiry)
        if (expiry != null && !expiry.isAfter(now)) cookies.remove(cookie.key) else cookies[cookie.key] = cookie
    }

    /** The `Cookie` header value for a request to [url], or null when no stored cookie applies. */
    @Synchronized
    fun headerFor(url: String): String? {
        val uri = parse(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.takeUnless { it.isNullOrEmpty() } ?: "/"
        val https = uri.scheme.equals("https", ignoreCase = true)
        val now = clock()
        cookies.values.removeIf { it.expires != null && !it.expires.isAfter(now) }
        val matching = cookies.values
            .filter { (!it.secure || https) && (if (it.hostOnly) host == it.domain else domainMatches(host, it.domain)) && pathMatches(path, it.path) }
            .sortedByDescending { it.path.length }
        return matching.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (e: Exception) {
        null
    }

    private fun domainMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    private fun pathMatches(request: String, cookie: String) =
        request == cookie || (request.startsWith(cookie) && (cookie.endsWith("/") || request[cookie.length] == '/'))

    private fun defaultPath(requestPath: String?): String {
        if (requestPath.isNullOrEmpty() || !requestPath.startsWith("/")) return "/"
        val i = requestPath.lastIndexOf('/')
        return if (i <= 0) "/" else requestPath.substring(0, i)
    }

    private companion object {
        const val ONE_HUNDRED_YEARS = 100L * 365 * 24 * 3600
    }
}
