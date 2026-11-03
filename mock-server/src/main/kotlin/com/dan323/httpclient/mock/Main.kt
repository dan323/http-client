package com.dan323.httpclient.mock

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val server = MockServer(port).start()
    println("Mock server listening on http://localhost:${server.port} (Ctrl+C to stop)")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
