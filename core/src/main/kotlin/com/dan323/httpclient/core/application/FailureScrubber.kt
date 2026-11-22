package com.dan323.httpclient.core.application

import java.net.URLEncoder

/**
 * Keeps substituted variable values (tokens, passwords) out of failure messages: exception texts such as
 * `URI.create` errors echo the whole resolved URL. Each occurrence of a substituted value, raw or
 * URL-encoded, is replaced by `{{name}}` so the message still says which variable was involved.
 * Values shorter than [MIN_LENGTH] characters are left alone (replacing them would only garble the text).
 *
 * Occurrences that overlap (`xT` and `TOPSECRET` in `xTOPSECRET`) are hidden as one stretch, so no piece of any value is
 * left showing; a value found inside a longer one adds no name of its own. The search is one pass over the text with an
 * Aho-Corasick automaton: its cost grows with the length of the text, not with the length or the number of the values, and
 * it holds only the stretches that a later match could still join.
 */
internal object FailureScrubber {
    const val MIN_LENGTH = 3

    /** [substituted] maps value to the variable name it came from. */
    fun scrub(message: String, substituted: Map<String, String>, minLength: Int = MIN_LENGTH): String =
        automaton(substituted, minLength)?.let { scrub(message, it) } ?: message

    fun scrub(message: String, automaton: Automaton): String {
        val out = StringBuilder()
        var at = 0
        for (g in automaton.groups(message, message.length)) {
            out.append(message, at, g.start).append(g.placeholder())
            at = g.end
        }
        return out.append(message, at, message.length).toString()
    }

    fun scrubPrefix(text: String, substituted: Map<String, String>, keep: Int, minLength: Int = MIN_LENGTH, limit: Int = Int.MAX_VALUE): String {
        val a = automaton(substituted, minLength)
        return if (a == null) text.substring(0, minOf(keep.coerceIn(0, text.length), limit)) else scrubPrefix(text, a, keep, limit)
    }

    /**
     * The first [keep] characters of [text], scrubbed, and nothing after them: a value that starts inside them is hidden
     * whole even when it runs past [keep], and one that starts at or after [keep] is not shown. The result therefore never
     * holds a piece of a value cut by the edge, and, unlike scrubbing and then cutting, does not depend on how long the
     * placeholders are. Once [limit] characters of output exist the work stops (the result may be a little longer).
     */
    fun scrubPrefix(text: String, automaton: Automaton, keep: Int, limit: Int = Int.MAX_VALUE): String {
        val end = keep.coerceIn(0, text.length)
        val out = StringBuilder()
        var at = 0
        // a match that starts before `end` ends before end + longest: nothing further needs to be read
        val scanEnd = if (end > text.length - automaton.longest) text.length else end + automaton.longest
        for (g in automaton.groups(text, scanEnd)) {
            if (g.start >= end) break
            val room = limit - out.length
            if (g.start - at >= room) return out.append(text, at, at + room).toString()
            out.append(text, at, g.start).append(g.placeholder())
            at = g.end
            if (out.length >= limit) return out.toString()
        }
        if (at < end) out.append(text, at, if (end - at <= limit - out.length) end else at + (limit - out.length))
        return out.toString()
    }

    /**
     * The automaton for [substituted] (every value, its URL-encoded forms), or null when there is nothing to hide. With
     * [lowerCase] every form is lower-cased after it was encoded, for a text that was lower-cased itself (the hex digits of
     * `%C3%A9` become `%c3%a9`, as they are in a lower-cased header name).
     */
    fun automaton(substituted: Map<String, String>, minLength: Int = MIN_LENGTH, lowerCase: Boolean = false): Automaton? {
        val forms = LinkedHashMap<String, String>() // text to hide -> variable name
        fun add(form: String, name: String) { forms.putIfAbsent(if (lowerCase) form.lowercase() else form, name) }
        for ((value, name) in substituted) {
            if (value.isEmpty() || value.length < minLength) continue
            add(value, name.trim())
            val enc = URLEncoder.encode(value, Charsets.UTF_8)
            add(enc, name.trim())
            add(enc.replace("+", "%20"), name.trim())
        }
        return if (forms.isEmpty()) null else Automaton(forms)
    }

    /** A stretch of text to hide, with the variables whose values are in it (each once, in the order found). */
    class Group(var start: Int, var end: Int) {
        private val spans = LinkedHashMap<String, IntArray>() // name -> [first start, last end] of its occurrences here

        /** Adds an occurrence; the names of occurrences that lie wholly inside this one are dropped (the longer value says it all). */
        fun add(name: String, s: Int, e: Int) {
            spans.entries.removeAll { (n, span) -> n != name && span[0] >= s && span[1] <= e }
            val span = spans[name]
            if (span == null) spans[name] = intArrayOf(s, e) else { span[0] = minOf(span[0], s); span[1] = maxOf(span[1], e) }
            start = minOf(start, s)
            end = maxOf(end, e)
        }

        /** Takes over [lower], a stretch before this one that an occurrence now reaches back into. */
        fun absorb(lower: Group) {
            val mine = LinkedHashMap(spans)
            spans.clear()
            for ((n, span) in lower.spans) add(n, span[0], span[1])
            for ((n, span) in mine) add(n, span[0], span[1])
        }

        fun placeholder(): String = spans.keys.joinToString("") { "{{$it}}" }
    }

    /**
     * Aho-Corasick over the forms. At each position of the text it knows the longest form that ends there; any other form
     * ending there lies inside it, so these occurrences cover exactly what all of them cover.
     */
    class Automaton(forms: Map<String, String>) {
        private val edges = HashMap<Long, Int>() // (node, char) -> node
        private val fail = ArrayList<Int>().apply { add(0) }
        private val firstChild = ArrayList<Int>().apply { add(-1) }
        private val nextSibling = ArrayList<Int>().apply { add(-1) }
        private val charOf = ArrayList<Char>().apply { add(' ') }
        private val bestLength = ArrayList<Int>().apply { add(0) }
        private val bestName = ArrayList<String?>().apply { add(null) }

        /** Length of the longest form. */
        val longest: Int = forms.keys.maxOf { it.length }

        init {
            for ((form, name) in forms) {
                var node = 0
                for (ch in form) {
                    val key = key(node, ch)
                    node = edges[key] ?: newNode(node, ch).also { edges[key] = it }
                }
                bestLength[node] = form.length
                bestName[node] = name
            }
            // breadth first: a node's failure link points to a shallower node, already done
            val queue = ArrayDeque<Int>()
            var c = firstChild[0]
            while (c != -1) { queue.addLast(c); c = nextSibling[c] }
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                // the link of v was set when its parent was handled; a form that ends here beats the shorter ones that end at its link
                if (bestLength[v] == 0) { bestLength[v] = bestLength[fail[v]]; bestName[v] = bestName[fail[v]] }
                var child = firstChild[v]
                while (child != -1) {
                    val ch = charOf[child]
                    var g = fail[v]
                    while (g != 0 && !edges.containsKey(key(g, ch))) g = fail[g]
                    val target = edges[key(g, ch)]
                    fail[child] = if (target != null && target != child) target else 0
                    queue.addLast(child)
                    child = nextSibling[child]
                }
            }
        }

        private fun key(node: Int, ch: Char) = (node.toLong() shl 16) or ch.code.toLong()

        private fun newNode(parent: Int, ch: Char): Int {
            val id = fail.size
            fail.add(0); firstChild.add(-1); nextSibling.add(firstChild[parent]); charOf.add(ch); bestLength.add(0); bestName.add(null)
            firstChild[parent] = id
            return id
        }

        /**
         * The stretches to hide in `text[0, scanEnd)`, in order, one at a time. A stretch is held back only while a later
         * match could still reach into it (that is, for at most the length of the longest form), so a text made of
         * millions of matches is never held as a list of them.
         */
        fun groups(text: String, scanEnd: Int): Sequence<Group> = sequence {
            val pending = ArrayDeque<Group>()
            var state = 0
            for (i in 0 until minOf(text.length, scanEnd)) {
                val ch = text[i]
                while (state != 0 && !edges.containsKey(key(state, ch))) state = fail[state]
                state = edges[key(state, ch)] ?: 0
                val len = bestLength[state]
                if (len > 0) {
                    val s = i + 1 - len
                    val e = i + 1
                    val top = pending.lastOrNull()
                    if (top != null && top.end > s) {
                        // reaches into the stretch before it: they are one, and so is any earlier one it also reaches
                        while (pending.size >= 2 && pending[pending.size - 2].end > s) {
                            val lower = pending.removeAt(pending.size - 2)
                            top.absorb(lower)
                        }
                        top.add(bestName[state]!!, s, e)
                    } else {
                        pending.addLast(Group(s, e).also { it.add(bestName[state]!!, s, e) })
                    }
                }
                // no later match starts before i + 2 - longest: a stretch that ends by then is final
                while (pending.isNotEmpty() && pending.first().end <= i + 2 - longest) yield(pending.removeFirst())
            }
            for (g in pending) yield(g)
        }
    }
}
