package com.dan323.httpclient.core.application

import java.util.Base64

/**
 * JetBrains-compatible `Authorization: Basic` shortcut. After variable resolution a value
 * `Basic user password` or `Basic user:password` carries plain credentials and is rewritten to
 * `Basic <base64(user:password)>` (UTF-8). A value with a single token and no `:` is taken as already
 * encoded and left untouched, as is every other scheme.
 */
internal object BasicAuth {
    private val scheme = Regex("^Basic\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Returns the header value to send. [secret] is told every derived secret (the encoded value, the
     * `user:password` text and the password) so it can be kept out of failure messages.
     */
    fun encode(value: String, secret: (String) -> Unit = {}): String {
        val rest = scheme.find(value.trim())?.groupValues?.get(1)?.trim() ?: return value
        val colon = rest.indexOf(':')
        val (user, password) = if (colon >= 0) {
            rest.substring(0, colon) to rest.substring(colon + 1)
        } else {
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return value // one token: already encoded
            parts[0] to parts[1]
        }
        val plain = "$user:$password"
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        secret(encoded); secret(plain); secret(password)
        return "Basic $encoded"
    }
}
