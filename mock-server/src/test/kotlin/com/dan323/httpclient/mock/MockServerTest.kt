package com.dan323.httpclient.mock

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MockServerTest {
    private val server = MockServer(0).start()
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @AfterTest fun stop() = server.stop()

    private fun send(method: String, path: String, body: String? = null, vararg headers: Pair<String, String>) =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
                .method(method, body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody())
                .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
                .build(),
            BodyHandlers.ofString(),
        )

    @Test fun getEchoesQueryAndHeaders() {
        val r = send("GET", "/get?user=a%20b", null, "X-Custom" to "hi")
        assertEquals(200, r.statusCode())
        assertContains(r.body(), "\"user\": \"a b\"")
        assertContains(r.body(), "\"X-custom\": \"hi\"")
    }

    @Test fun postEchoesJsonAndForm() {
        val json = send("POST", "/post", """{"token":"t"}""", "Content-Type" to "application/json")
        assertContains(json.body(), "\"token\": \"t\"")
        val form = send("POST", "/post", "a=1&b=2", "Content-Type" to "application/x-www-form-urlencoded")
        assertContains(form.body(), "\"b\": \"2\"")
    }

    @Test fun postSplitsMultipartIntoFormAndFiles() {
        val body = "--B\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nhello\r\n" +
            "--B\r\nContent-Disposition: form-data; name=\"doc\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\nline1\nline2\r\n--B--\r\n"
        val r = send("POST", "/post", body, "Content-Type" to "multipart/form-data; boundary=B")
        assertContains(r.body(), "\"title\": \"hello\"")
        assertContains(r.body(), "\"doc\": \"line1\\nline2\"")
        assertEquals(1, Regex("\"doc\"").findAll(r.body()).count(), "the file is listed under files, not form")
    }

    @Test fun wrongMethodIs405AndUnknownPathIs404() {
        assertEquals(405, send("GET", "/post").statusCode())
        assertEquals(404, send("GET", "/nope").statusCode())
    }

    @Test fun statusRedirectAndHead() {
        assertEquals(404, send("GET", "/status/404").statusCode())
        assertEquals(204, send("GET", "/status/204").statusCode())
        val redirect = send("GET", "/redirect/2")
        assertEquals(302, redirect.statusCode())
        assertEquals("/redirect/1", redirect.headers().firstValue("Location").get())
        assertEquals(200, send("HEAD", "/get").statusCode())
        assertContains(send("OPTIONS", "/get").headers().firstValue("Allow").get(), "GET")
    }

    @Test fun bearerRequiresToken() {
        assertEquals(401, send("GET", "/bearer").statusCode())
        val ok = send("GET", "/bearer", null, "Authorization" to "Bearer abc")
        assertEquals(200, ok.statusCode())
        assertContains(ok.body(), "\"token\": \"abc\"")
    }
}
