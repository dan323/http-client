package com.dan323.httpclient.mcp

import com.dan323.httpclient.adapters.GuardedHttpExecutor
import com.dan323.httpclient.adapters.HostAllowList
import com.dan323.httpclient.adapters.JdkHttpExecutor
import com.dan323.httpclient.adapters.SessionFactory
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.port.out.EnvironmentVariables
import java.io.PrintStream
import kotlin.system.exitProcess

/** Wires the server from the start flags: guard and limits around the JDK client, a factory without persistence. */
fun buildServer(options: ServerOptions, processEnv: EnvironmentVariables = SystemEnvironmentVariables(options.allowEnv)): Pair<McpServer, AutoCloseable> {
    // a response is read at most up to maxResponseBytes, whatever the server sends (the tool output is cut much sooner)
    val jdk = JdkHttpExecutor(maxBodyBytes = options.maxResponseBytes)
    val limits = CallLimits(jdk, options.maxRequests, options.totalMillis)
    val resolver = BoundedResolver(remaining = limits::remainingMillis)
    val hosts = HostAllowList(options.allowHosts ?: HostAllowList.DEFAULT, resolver)
    val guarded = GuardedHttpExecutor(limits, hosts, options.allowWrite, options.requestTimeoutMillis)
    val factory = SessionFactory(guarded)
    return McpServer(Tools(options, factory, limits, hosts, processEnv)) to AutoCloseable { factory.close(); jdk.close(); resolver.close() }
}

fun main(args: Array<String>) {
    // stdout is the protocol channel: everything meant for the user goes to stderr
    val err: PrintStream = System.err
    val options = ServerOptions.parse(args.toList()).getOrElse {
        err.println("httpc-mcp: ${it.message}\n${ServerOptions.USAGE}")
        exitProcess(2)
    }
    if (options.allowWrite) {
        err.println("httpc-mcp: --allow-write: requests that change data (POST, PUT, PATCH, DELETE) are allowed; the client is asked to confirm each call that sends one" +
            if (options.allowUnconfirmedWrite) ", and WITHOUT asking when it cannot (--allow-unconfirmed-write)." else ", and such a call is refused when it cannot ask.")
    }
    err.println("httpc-mcp: roots ${options.roots.joinToString()}, hosts ${options.allowHosts?.joinToString() ?: "loopback only"}")
    val out = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, Charsets.UTF_8)
    val (server, closeable) = buildServer(options)
    closeable.use { server.serve(System.`in`.bufferedReader(Charsets.UTF_8), out) }
}
