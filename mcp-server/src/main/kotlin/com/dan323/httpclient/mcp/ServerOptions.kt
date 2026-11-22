package com.dan323.httpclient.mcp

import com.dan323.httpclient.adapters.HostAllowList
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.application.RequestTimeouts
import java.nio.file.Files
import java.nio.file.Path

/** Start flags: every permission of the server comes from here, never from a tool call (docs/mcp-safety-model.md). */
data class ServerOptions(
    val roots: List<Path>,
    val allowHosts: List<String>? = null,
    val allowWrite: Boolean = false,
    /** With [allowWrite], also send them when the client cannot ask the user to confirm (otherwise such a call is refused). */
    val allowUnconfirmedWrite: Boolean = false,
    val allowEnv: Set<String> = emptySet(),
    val maxRequests: Int = 100,
    val totalMillis: Long = 5 * 60_000,
    val requestTimeoutMillis: Long = 60_000,
    val maxBodyBytes: Int = 64 * 1024,
) {
    companion object {
        const val USAGE = """Usage: httpc-mcp --root DIR [--root DIR ...] [--allow-host H ...] [--allow-write [--allow-unconfirmed-write]] [--allow-env NAME[,NAME]]
                 [--max-requests N] [--total-timeout D] [--request-timeout D] [--max-body-bytes N]

MCP server on stdio. Tools only see .http files under a --root. Requests may only go to the allowed hosts
(default: loopback; an exact name, *.suffix, an IP or a CIDR range), only GET/HEAD/OPTIONS run unless
--allow-write is given (each call that sends one is confirmed by the user through the client; --allow-unconfirmed-write
lets them through for a client that cannot ask), and values from environments, ${'$'}env and earlier responses are hidden in what is returned."""

        /** Parsed options, or a message for the user. */
        fun parse(args: List<String>): Result<ServerOptions> = try { Result.success(parseOrThrow(args)) } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }

        private fun parseOrThrow(args: List<String>): ServerOptions {
            val roots = mutableListOf<Path>()
            var hosts: MutableList<String>? = null
            var write = false
            var unconfirmed = false
            val env = mutableSetOf<String>()
            var maxRequests = 100
            var total = 5 * 60_000L
            var perRequest = 60_000L
            var maxBody = 64 * 1024
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i)?.takeIf { it.isNotEmpty() && !it.startsWith("--") }
                ?: throw IllegalArgumentException("$flag needs a value")
            fun positive(flag: String): Int = value(flag).toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag needs a positive number")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--root" -> {
                        val p = Path.of(value(a))
                        require(p.isAbsolute) { "--root must be an absolute path" }
                        require(Files.isDirectory(p)) { "--root: not a directory: $p" }
                        roots.add(p.toRealPath())
                    }
                    "--allow-host" -> (hosts ?: mutableListOf<String>().also { hosts = it }) += value(a)
                    "--allow-write" -> write = true
                    "--allow-unconfirmed-write" -> unconfirmed = true
                    "--allow-env" -> env += SystemEnvironmentVariables.parseNames(value(a))
                    "--max-requests" -> maxRequests = positive(a)
                    "--max-body-bytes" -> maxBody = positive(a)
                    "--total-timeout" -> total = RequestTimeouts.parseMillis(value(a), label = a)
                    "--request-timeout" -> perRequest = RequestTimeouts.parseMillis(value(a), label = a)
                    else -> throw IllegalArgumentException("Unknown or misplaced argument: $a")
                }
                i++
            }
            require(!unconfirmed || write) { "--allow-unconfirmed-write only makes sense with --allow-write" }
            require(roots.isNotEmpty()) { "At least one --root DIR is required" }
            hosts?.let { HostAllowList(it) } // fail at start on a bad entry
            return ServerOptions(roots, hosts, write, unconfirmed, env, maxRequests, total, perRequest, maxBody)
        }
    }
}
