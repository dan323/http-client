package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.HttpRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyLimitAndEnvTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun tearDown() = server.stop(0)

    private fun serve(path: String, body: ByteArray) {
        server.createContext(path) { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        server.start()
    }

    @Test fun aLongBodyIsCutWhileItStreams() {
        serve("/big", ByteArray(1_000_000) { 'a'.code.toByte() })
        JdkHttpExecutor(maxBodyBytes = 1000).use {
            val r = it.execute(HttpRequest("GET", "$base/big"))
            assertEquals(200, r.statusCode); assertEquals(1000, r.body.size)
        }
    }

    @Test fun aShortBodyIsKeptWhole() {
        serve("/small", "hello".toByteArray())
        JdkHttpExecutor(maxBodyBytes = 1000).use { assertEquals("hello", it.execute(HttpRequest("GET", "$base/small")).bodyAsString()) }
    }

    @Test fun noLimitKeepsTheWholeBody() {
        serve("/mid", ByteArray(5000))
        JdkHttpExecutor().use { assertEquals(5000, it.execute(HttpRequest("GET", "$base/mid")).body.size) }
    }

    @Test fun theLimitMustBePositive() { assertFailsWith<IllegalArgumentException> { JdkHttpExecutor(maxBodyBytes = 0) } }

    @Test fun ambientEnvironmentPermissionsCanBeIgnored() {
        val dir = Files.createTempDirectory("amb")
        val file = dir.resolve("a.http").also { Files.writeString(it, "GET http://h/") }
        val previous = System.getProperty(SystemEnvironmentVariables.ALLOW_PROPERTY)
        System.setProperty(SystemEnvironmentVariables.ALLOW_PROPERTY, "PATH")
        try {
            val read = { ambient: Boolean ->
                SessionFactory().use { f ->
                    val s = f.create(file, FileAccessPolicy(), ambientEnvironment = ambient)
                    runCatching { s.run(com.dan323.httpclient.core.domain.RequestTemplate(null, "GET", "http://127.0.0.1:1/{{\$env.PATH}}"), emptyMap()) }
                        .exceptionOrNull()?.message.orEmpty()
                }
            }
            assertFalse(read(true).contains("allow", ignoreCase = true), "ambient permission is honoured by default")
            assertTrue(read(false).contains("allow", ignoreCase = true), "ignored when ambientEnvironment is false: ${read(false)}")
        } finally {
            if (previous == null) System.clearProperty(SystemEnvironmentVariables.ALLOW_PROPERTY) else System.setProperty(SystemEnvironmentVariables.ALLOW_PROPERTY, previous)
            dir.toFile().deleteRecursively()
        }
    }
}
