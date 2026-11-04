package com.dan323.httpclient.core.application

/** Thrown by [RegexGuard] when a regex runs longer than its budget (catastrophic backtracking). */
class RegexTimeoutException : RuntimeException("regex took too long (possible catastrophic backtracking)")

/**
 * A [CharSequence] view that throws [RegexTimeoutException] once [millis] have elapsed since it was created.
 * `java.util.regex` reads its input through `charAt`, so wrapping the subject text bounds a runaway match
 * without any dependency.
 */
internal class RegexGuard(private val text: CharSequence, millis: Long = 2_000) : CharSequence {
    private val deadline = System.nanoTime() + millis * 1_000_000
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads and 0x3FF == 0 && System.nanoTime() > deadline) throw RegexTimeoutException()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()
}
