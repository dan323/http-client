package com.dan323.httpclient.adapters

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** A request that a guard refuses before anything is sent; the message names the cause and the start flag to change. */
class RequestRefusedException(message: String) : RuntimeException(message)

/**
 * Which hosts a request may reach (the host rules of docs/mcp-safety-model.md).
 *
 * Entries: an exact name, `*.suffix` (any depth below the suffix, never the bare suffix), an IP address or a CIDR range.
 * A literal-IP URL must be inside a listed IP or range. A name must match a name entry, and **every** address it
 * resolves to must be inside a listed IP or range or be a public address: loopback, link-local, private, unique-local,
 * shared (`100.64.0.0/10`), unspecified and multicast addresses are reachable only when listed, so `*.nip.io` cannot
 * lead to the cloud metadata address. Numeric host forms the JDK would still read as an address (`2130706433`, `0x7f.1`)
 * are refused. The default allows `localhost` and the loopback ranges.
 *
 * The JDK client resolves the name again when it connects, so a name that changes its answer in between (DNS rebinding)
 * is not caught; list an IP literal to rule that out.
 */
class HostAllowList(entries: Collection<String> = DEFAULT, private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) {
    private val names = mutableListOf<String>()
    private val ranges = mutableListOf<Range>()

    init {
        entries.forEach { raw ->
            val e = raw.trim().lowercase().trimEnd('.')
            require(e.isNotEmpty()) { "Empty host entry" }
            if (e.contains('/') || isLiteral(e)) ranges += Range.parse(e) else {
                require(e.none { it.isWhitespace() || it == '@' || it == ':' } && (!e.startsWith("*") || e.startsWith("*.") && e.length > 2)) { "Invalid host entry: $raw" }
                names += e
            }
        }
    }

    /** Throws [RequestRefusedException] unless [url] may be sent to. */
    fun check(url: String) {
        val uri = try { URI(url) } catch (e: Exception) { throw refused("not a valid URL") }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) throw refused("only http and https URLs are allowed")
        val rawHost = uri.host ?: throw refused("no host in the URL")
        val host = rawHost.lowercase().trimEnd('.')
        if (host.contains('%')) throw refused("'$host' has a zone id")
        if (host.startsWith("[") || isLiteral(host)) {
            val address = literal(host) ?: throw refused("'$host' is not a plain IP address")
            if (ranges.none { it.contains(address) }) throw refused("'$host' is not in the allowed addresses")
            return
        }
        if (NUMERIC.matches(host)) throw refused("'$host' is a numeric host form")
        if (!matchesName(host)) throw refused("'$host' is not an allowed host")
        val addresses = try { resolve(host) } catch (e: java.net.UnknownHostException) { throw refused("cannot resolve '$host'") }
        if (addresses.isEmpty()) throw refused("cannot resolve '$host'")
        addresses.forEach { a ->
            if (ranges.none { it.contains(a) } && restricted(a)) throw refused("'$host' resolves to ${a.hostAddress}, which is not in the allowed addresses")
        }
    }

    private fun refused(why: String) = RequestRefusedException("Request refused: $why (allowed hosts are set with --allow-host)")

    private fun matchesName(host: String) = names.any { n -> if (n.startsWith("*.")) host.endsWith(n.substring(1)) && host.length > n.length - 1 else host == n }

    private class Range(val network: ByteArray, val prefix: Int) {
        fun contains(a: InetAddress): Boolean {
            val b = a.address
            if (b.size != network.size) return false
            val x = BigInteger(1, b).xor(BigInteger(1, network)).shiftRight(b.size * 8 - prefix)
            return x.signum() == 0
        }

        companion object {
            fun parse(entry: String): Range {
                val slash = entry.indexOf('/')
                val ip = literal(if (slash < 0) entry else entry.substring(0, slash)) ?: throw IllegalArgumentException("Invalid host entry: $entry")
                val bits = ip.address.size * 8
                val prefix = if (slash < 0) bits else entry.substring(slash + 1).toIntOrNull() ?: throw IllegalArgumentException("Invalid host entry: $entry")
                require(prefix in 0..bits) { "Invalid prefix in host entry: $entry" }
                return Range(ip.address, prefix)
            }
        }
    }

    companion object {
        /** `localhost` and the loopback ranges. */
        val DEFAULT = listOf("localhost", "127.0.0.0/8", "::1")

        private val NUMERIC = Regex("(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*")
        private val DOTTED = Regex("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}")

        private fun isLiteral(s: String) = s.startsWith("[") || s.contains(':') || DOTTED.matches(s.substringBefore('/'))

        /** An IPv4 dotted quad or an IPv6 address (brackets allowed) without any name lookup; IPv4-mapped IPv6 becomes IPv4. */
        private fun literal(s: String): InetAddress? {
            val t = s.removePrefix("[").removeSuffix("]")
            if (!t.contains(':') && !DOTTED.matches(t)) return null
            return try { InetAddress.getByName(t) } catch (e: Exception) { null }
        }

        private fun restricted(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> (b[0].toInt() and 0xff).let { it == 0 || it == 100 && (b[1].toInt() and 0xc0) == 0x40 || it >= 240 }
                is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
                else -> true
            }
        }
    }
}
