package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A small in-memory cookie store (RFC 6265 subset) owned by one [RequestSession], so cookies are never
 * shared between sessions. Supports Domain (host-only when absent), Path, Max-Age, Expires and Secure.
 *
 * Limits of this implementation:
 * - There is no full public-suffix list (core has no third-party dependencies). `Domain` attributes with a
 *   single label (`com`) and a short built-in list of common two-label suffixes (`co.uk`, ...) are rejected
 *   unless they equal the request host; other registry-controlled suffixes not in that list are not detected.
 * - `Secure` cookies are only accepted from, and sent over, https (no loopback exception), and a response
 *   from plain http never overwrites or deletes an existing `Secure` cookie.
 * - Size limits: a cookie (name + value) over [MAX_COOKIE_BYTES] is ignored; at most [MAX_PER_DOMAIN] cookies
 *   per domain and [MAX_TOTAL] overall, evicting the oldest first. "Oldest" is by first creation: replacing an
 *   existing cookie keeps its place in the [LinkedHashMap], which matches the RFC creation-time rule.
 * - Redirects are not handled here; [RequestSession] follows them hop by hop and calls [store]/[headerFor]
 *   for each hop.
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

    /** Number of live (unexpired) cookies, mostly for tests and diagnostics. */
    @Synchronized
    fun size(): Int {
        purgeExpired(clock())
        return cookies.size
    }

    /** Stores the `Set-Cookie` headers of a response received from [url]. Malformed ones are ignored. */
    @Synchronized
    fun store(url: String, headers: List<Header>) {
        val uri = parse(url) ?: return
        val host = hostOf(uri) ?: return
        val https = uri.scheme.equals("https", ignoreCase = true)
        headers.filter { it.name.equals("Set-Cookie", ignoreCase = true) }
            .forEach { store(host, https, uri.rawPath, it.value) }
    }

    private fun store(host: String, https: Boolean, requestPath: String?, raw: String) {
        val parts = raw.split(';')
        val pair = parts[0]
        val eq = pair.indexOf('=')
        if (eq <= 0) return
        val name = pair.substring(0, eq).trim()
        if (name.isEmpty()) return
        val value = pair.substring(eq + 1).trim()
        if (name.length + value.length > MAX_COOKIE_BYTES) return
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
                "max-age" -> maxAge = parseMaxAge(v) ?: maxAge
                "expires" -> expires = parseCookieDate(v) ?: expires
            }
        }
        if (secure && !https) return // RFC 6265bis: a Secure cookie must not be set over an insecure channel
        var hostOnly = true
        var cookieDomain = host
        if (domain != null) {
            if (isIpLiteral(host)) {
                if (domain != host) return // IP literals only ever match exactly
            } else if (isPublicSuffix(domain)) {
                if (domain != host) return // supercookie attempt (Domain=com, Domain=co.uk)
            } else {
                if (!domainMatches(host, domain)) return // a host may not set cookies for other domains
                hostOnly = false
                cookieDomain = domain
            }
        }
        val now = clock()
        val expiry = maxAge?.let { saturatedPlus(now, it) } ?: expires
        val cookie = Cookie(name, value, cookieDomain, hostOnly, path ?: defaultPath(requestPath), secure, expiry)
        // An insecure origin may neither replace nor delete a Secure cookie.
        if (!https && cookies[cookie.key]?.secure == true) return
        purgeExpired(now)
        if (expiry != null && !expiry.isAfter(now)) {
            cookies.remove(cookie.key)
            return
        }
        cookies[cookie.key] = cookie
        evict(cookie.domain)
    }

    private fun evict(domain: String) {
        val perDomain = cookies.values.filter { it.domain == domain }
        if (perDomain.size > MAX_PER_DOMAIN) perDomain.take(perDomain.size - MAX_PER_DOMAIN).forEach { cookies.remove(it.key) }
        if (cookies.size > MAX_TOTAL) cookies.keys.take(cookies.size - MAX_TOTAL).forEach { cookies.remove(it) }
    }

    private fun purgeExpired(now: Instant) {
        cookies.values.removeIf { it.expires != null && !it.expires.isAfter(now) }
    }

    /** The `Cookie` header value for a request to [url], or null when no stored cookie applies. */
    @Synchronized
    fun headerFor(url: String): String? {
        val uri = parse(url) ?: return null
        val host = hostOf(uri) ?: return null
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val https = uri.scheme.equals("https", ignoreCase = true)
        purgeExpired(clock())
        val ip = isIpLiteral(host)
        val matching = cookies.values
            .filter { (!it.secure || https) && (if (it.hostOnly || ip) host == it.domain else domainMatches(host, it.domain)) && pathMatches(path, it.path) }
            .sortedByDescending { it.path.length } // stable: ties keep creation order
        return matching.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (e: Exception) {
        null
    }

    /** `URI.host` is null for names such as `my_service`, so fall back to the authority; IPv6 brackets are removed. */
    private fun hostOf(uri: URI): String? {
        val h = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.let { a ->
            if (a.startsWith("[")) a.substringBefore(']') + "]" else a.substringBefore(':')
        }
        return h?.removeSurrounding("[", "]")?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private fun domainMatches(host: String, domain: String) = host == domain || (!isIpLiteral(host) && host.endsWith(".$domain"))

    private fun pathMatches(request: String, cookie: String) =
        request == cookie || (request.startsWith(cookie) && (cookie.endsWith("/") || request[cookie.length] == '/'))

    private fun defaultPath(requestPath: String?): String {
        if (requestPath.isNullOrEmpty() || !requestPath.startsWith("/")) return "/"
        val i = requestPath.lastIndexOf('/')
        return if (i <= 0) "/" else requestPath.substring(0, i)
    }

    private fun saturatedPlus(now: Instant, seconds: Long): Instant = now.plusSeconds(seconds.coerceIn(-ONE_HUNDRED_YEARS, ONE_HUNDRED_YEARS))

    internal companion object {
        const val ONE_HUNDRED_YEARS = 100L * 365 * 24 * 3600
        const val MAX_COOKIE_BYTES = 4096
        const val MAX_PER_DOMAIN = 50
        const val MAX_TOTAL = 3000

        private val maxAgePattern = Regex("-?\\d+")
        private val ipv4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

        /** RFC 6265 5.2.2: digits with an optional leading `-` only (`+5` is ignored); huge values saturate. */
        fun parseMaxAge(v: String): Long? {
            if (!maxAgePattern.matches(v)) return null
            return v.toLongOrNull() ?: if (v.startsWith("-")) Long.MIN_VALUE else Long.MAX_VALUE
        }

        fun isIpLiteral(host: String) = ':' in host || ipv4.matches(host)

        // Common registry-controlled two-label suffixes; a stand-in for the public suffix list.
        private val twoLabelSuffixes = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "org.nz", "net.nz", "govt.nz",
            "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp", "com.br", "net.br", "org.br", "gov.br",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.mx", "com.ar", "com.tr", "com.tw",
            "com.hk", "com.sg", "com.my", "com.ph", "com.vn", "co.in", "net.in", "org.in", "co.kr",
            "or.kr", "co.za", "org.za", "co.il", "co.id", "co.th", "com.ua", "com.pl", "com.ru",
        )

        /** True for `com`-style single labels and the built-in two-label suffixes above (no full PSL). */
        fun isPublicSuffix(domain: String) = '.' !in domain || domain in twoLabelSuffixes

        private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /**
         * RFC 6265 5.1.1 lenient date parsing: tokenizes on delimiters, so `Wed, 21 Oct 2015 07:28:00 GMT`,
         * `Wed, 21-Oct-2015 07:28:00 GMT` and `Wednesday, 21-Oct-15 07:28:00 GMT` (2-digit year) all work.
         * Returns null when the date is unusable.
         */
        fun parseCookieDate(s: String): Instant? {
            var time: Triple<Int, Int, Int>? = null
            var day: Int? = null
            var month: Int? = null
            var year: Int? = null
            for (tok in s.split(Regex("[\\t\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+")).filter { it.isNotEmpty() }) {
                val t = Regex("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:\\D.*)?").matchEntire(tok)
                if (time == null && t != null) {
                    time = Triple(t.groupValues[1].toInt(), t.groupValues[2].toInt(), t.groupValues[3].toInt())
                    continue
                }
                val digits = tok.takeWhile { it.isDigit() }
                if (day == null && digits.length in 1..2) {
                    day = digits.toInt()
                    continue
                }
                if (month == null && tok.length >= 3 && tok.take(3).lowercase() in months) {
                    month = months.indexOf(tok.take(3).lowercase()) + 1
                    continue
                }
                if (year == null && digits.length in 2..4) {
                    year = digits.toInt()
                    continue
                }
            }
            if (time == null || day == null || month == null || year == null) return null
            if (year in 70..99) year += 1900 else if (year in 0..69) year += 2000
            if (year < 1601 || day !in 1..31 || time.first > 23 || time.second > 59 || time.third > 59) return null
            return try {
                LocalDateTime.of(year, month, day, time.first, time.second, time.third).toInstant(ZoneOffset.UTC)
            } catch (e: Exception) {
                null
            }
        }
    }
}
