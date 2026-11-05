package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions

/**
 * Turns `# @setting` directives into [RequestOptions] (names are case-insensitive, unknown ones are ignored):
 * - `timeout`: whole number with optional `ms`/`s`/`m` suffix (any case), default seconds, must be positive.
 * - `no-redirect`: presence disables redirects; an optional value `true`/`false` (may use `{{vars}}`).
 * - `no-cookie-jar`: like `no-redirect`; the session's cookie jar neither sends nor stores cookies. It cannot disable a `CookieHandler` configured on an injected `HttpClient`.
 * - `proxy`: `[http://]host[:port]` (port defaults to 80; IPv6 as `[::1]:8080`); no credentials, http only.
 * Invalid values fail with an `Invalid @setting` message (an [IllegalArgumentException]) that quotes the setting as written.
 */
internal object RequestOptionsParser {
    private val timeoutPattern = Regex("""(\d+)\s*(ms|s|m)?""", RegexOption.IGNORE_CASE)

    /** [text] substitutes `{{variables}}` in a setting value. */
    fun parse(settings: Map<String, String>, text: (String) -> String): RequestOptions {
        val s = settings.mapKeys { it.key.lowercase() }
        // Validation errors quote the setting as written (`{{t}}`), never the resolved value, which may be a secret.
        fun <T> setting(name: String, parse: (resolved: String, written: String) -> T): T? =
            s[name]?.let { written -> parse(text(written).trim(), written.trim()) }
        return RequestOptions(
            timeoutMillis = setting("timeout", ::parseTimeout),
            followRedirects = setting("no-redirect") { v, w -> parseFlag("no-redirect", v, w) }?.not() ?: true,
            proxy = setting("proxy", ::parseProxy),
            useCookieJar = setting("no-cookie-jar") { v, w -> parseFlag("no-cookie-jar", v, w) }?.not() ?: true,
        )
    }

    private fun parseFlag(name: String, raw: String, written: String): Boolean = when (raw.lowercase()) {
        "", "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid @$name value: '$written' (expected true or false)")
    }

    private fun parseTimeout(raw: String, written: String): Long {
        val m = timeoutPattern.matchEntire(raw) ?: throw IllegalArgumentException("Invalid @timeout value: '$written'")
        val n = m.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid @timeout value: '$written' (too large)")
        val factor = when (m.groupValues[2].lowercase()) {
            "ms" -> 1L
            "m" -> 60_000L
            else -> 1000L
        }
        val millis = try {
            Math.multiplyExact(n, factor)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Invalid @timeout value: '$written' (too large)")
        }
        require(millis > 0) { "Invalid @timeout value: '$written' (must be greater than zero)" }
        return millis
    }

    private fun parseProxy(raw: String, written: String): ProxyAddress {
        fun bad(why: String): Nothing = throw IllegalArgumentException("Invalid @proxy value: '$written' ($why)")
        if (raw.isEmpty()) bad("empty")
        val scheme = raw.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty() && scheme != "http") bad("only http proxies are supported")
        val u = try {
            java.net.URI(if ("://" in raw) raw else "http://$raw")
        } catch (e: java.net.URISyntaxException) {
            bad("expected host:port, use [::1]:8080 for IPv6")
        }
        if (u.userInfo != null) bad("credentials are not supported")
        val host = u.host?.removeSurrounding("[", "]") ?: bad("expected host:port, use [::1]:8080 for IPv6")
        if (!u.path.isNullOrEmpty() && u.path != "/" || u.query != null || u.fragment != null) bad("unexpected path or query")
        return try {
            ProxyAddress(host, if (u.port == -1) 80 else u.port)
        } catch (e: IllegalArgumentException) {
            bad(e.message?.substringAfter(": ") ?: "invalid")
        }
    }
}
