package com.dan323.httpclient.core.application

/**
 * The Mozilla Public Suffix List (ICANN and private sections), bundled as the classpath resource
 * `public_suffix_list.txt` next to this package's classes (rules only, comments stripped, so core keeps zero
 * third-party dependencies). Used by [CookieJar] to refuse `Domain` attributes that name a registry-controlled
 * suffix such as `com`, `co.uk` or `github.io`. Update procedure: see "Cookie jar" in `docs/features.md`.
 */
internal object PublicSuffixList {
    private class Rules(val plain: Set<String>, val wildcardParents: Set<String>, val exceptions: Set<String>)

    private const val RESOURCE = "/com/dan323/httpclient/core/public_suffix_list.txt"

    private val rules: Rules by lazy { load() }

    private fun load(): Rules {
        val plain = HashSet<String>()
        val wildcards = HashSet<String>()
        val exceptions = HashSet<String>()
        val stream = PublicSuffixList::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the classpath; cookie Domain checks cannot run without it")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val rule = line.trim().lowercase()
                when {
                    rule.isEmpty() || rule.startsWith("//") -> {}
                    rule.startsWith("!") -> exceptions += rule.substring(1)
                    rule.startsWith("*.") -> wildcards += rule.substring(2)
                    else -> plain += rule
                }
            }
        }
        return Rules(plain, wildcards, exceptions)
    }

    /** Number of rules loaded from the bundled list; fails if the resource is missing. */
    fun ruleCount(): Int = rules.let { it.plain.size + it.wildcardParents.size + it.exceptions.size }

    /** Number of labels of the public suffix of [labels] (the PSL algorithm; the implicit rule `*` gives 1). */
    private fun suffixLength(labels: List<String>): Int {
        val r = rules
        val n = labels.size
        var best = 1
        for (i in 0 until n) {
            val candidate = labels.subList(i, n).joinToString(".")
            if (candidate in r.exceptions) return n - i - 1 // an exception always prevails
            if (candidate in r.plain) best = maxOf(best, n - i)
            if (i + 1 < n && labels.subList(i + 1, n).joinToString(".") in r.wildcardParents) best = maxOf(best, n - i)
        }
        return best
    }

    /** True when [domain] (lowercase, no leading dot) is itself a public suffix. */
    fun isPublicSuffix(domain: String): Boolean {
        val labels = domain.trimEnd('.').split('.')
        return labels.size <= suffixLength(labels)
    }
}
