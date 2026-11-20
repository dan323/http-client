package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.BrunoConverter
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpFileWriter
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.PartContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrunoConverterTest {
    private fun convert(text: String) = BrunoConverter.request(text.trimIndent() + "\n", "fallback")

    @Test fun getWithHeadersAndSequence() {
        val r = convert("""
            meta {
              name: Get user
              type: http
              seq: 3
            }

            get {
              url: {{host}}/users/1
              body: none
              auth: none
            }

            headers {
              Accept: application/json
              ~X-Off: no
            }
        """)
        assertEquals(3, r.sequence)
        assertEquals("Get-user", r.template.name)
        assertEquals("GET", r.template.method)
        assertEquals("{{host}}/users/1", r.template.url)
        assertEquals(listOf(Header("Accept", "application/json")), r.template.headers)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun jsonBodyAddsContentType() {
        val r = convert("""
            post {
              url: http://h/x
              body: json
            }

            body:json {
              {
                "a": 1
              }
            }
        """)
        assertEquals(Body.Text("{\n  \"a\": 1\n}"), r.template.body)
        assertEquals(Header("Content-Type", "application/json"), r.template.headers.single())
        assertEquals("fallback", r.template.name)
    }

    @Test fun existingContentTypeIsKept() {
        val r = convert("""
            post {
              url: http://h/x
              body: text
            }

            headers {
              content-type: text/csv
            }

            body:text {
              a,b
            }
        """)
        assertEquals(listOf(Header("content-type", "text/csv")), r.template.headers)
    }

    @Test fun formUrlEncodedSkipsDisabledFields() {
        val r = convert("""
            post {
              url: http://h/x
              body: formUrlEncoded
            }

            body:form-urlencoded {
              a: 1
              ~b: 2
              c: {{v}}
            }
        """)
        assertEquals(Body.Text("a=1&c={{v}}"), r.template.body)
        assertEquals("application/x-www-form-urlencoded", r.template.headers.single().value)
    }

    @Test fun multipartTextAndFileParts() {
        val r = convert("""
            post {
              url: http://h/x
              body: multipartForm
            }

            body:multipart-form {
              title: hello
              doc: @file(data/payload.json)
            }
        """)
        val body = r.template.body as Body.Multipart
        assertEquals(PartContent.Text("hello"), body.parts[0].content)
        assertEquals(PartContent.File("data/payload.json"), body.parts[1].content)
        assertTrue("filename=\"payload.json\"" in body.parts[1].headers.single().value)
        assertEquals("multipart/form-data; boundary=${body.boundary}", r.template.headers.single().value)
    }

    @Test fun pathAndQueryParams() {
        val r = convert("""
            get {
              url: http://h/users/:id?a=1
              body: none
            }

            params:query {
              a: 1
              b: two
              ~c: off
            }

            params:path {
              id: 42
            }
        """)
        assertEquals("http://h/users/42?a=1&b=two", r.template.url)
    }

    @Test fun basicAndBearerAuth() {
        val basic = convert("""
            get {
              url: http://h
              auth: basic
            }

            auth:basic {
              username: alice
              password: {{pw}}
            }
        """)
        assertEquals(Header("Authorization", "Basic alice:{{pw}}"), basic.template.headers.single())
        val bearer = convert("""
            get {
              url: http://h
              auth: bearer
            }

            auth:bearer {
              token: {{t}}
            }
        """)
        assertEquals(Header("Authorization", "Bearer {{t}}"), bearer.template.headers.single())
    }

    @Test fun unsupportedPartsAreReportedNotDropped() {
        val r = convert("""
            get {
              url: http://h
              body: graphql
              auth: oauth2
            }

            vars:pre-request {
              a: 1
            }

            script:post-response {
              bru.setVar("a", 1);
            }

            tests {
              test("x", () => {});
            }
        """)
        val all = r.warnings.joinToString("\n")
        for (expected in listOf("graphql", "oauth2", "vars:pre-request", "script:post-response", "'tests' (JavaScript)")) {
            assertTrue(expected in all, "missing warning about $expected in:\n$all")
        }
        assertNull(r.template.body)
    }

    @Test fun inheritedAuthIsReported() {
        val r = convert("""
            get {
              url: http://h
              auth: inherit
            }
        """)
        assertTrue(r.warnings.single().contains("inherited"))
    }

    @Test fun assertionsAreMapped() {
        val r = convert("""
            get {
              url: http://h
              body: none
            }

            assert {
              res.status: eq 200
              res.body.data.id: isDefined
              res.body.items[0].n: gt 3
              res.headers.content-type: contains json
              res.body.gone: isUndefined
              ~res.status: eq 500
              res.responseTime: lt 100
              res.body.x: isJson
            }
        """)
        assertEquals(
            listOf(
                "status == 200", "body.$.data.id exists", "body.$.items[0].n > 3",
                "headers.content-type contains json", "body.$.gone !exists",
            ),
            r.template.assertions,
        )
        assertEquals(2, r.warnings.size)
    }

    @Test fun processEnvIsRewritten() {
        val r = convert("""
            get {
              url: {{process.env.API}}/x
            }
        """)
        assertEquals("{{\$processEnv API}}/x", r.template.url)
    }

    @Test fun missingRequestBlockAndUnclosedBlock() {
        val none = convert("meta {\n  name: x\n}")
        assertTrue(none.warnings.single().contains("no request block"))
        val open = convert("get {\n  url: http://h\n")
        assertTrue(open.warnings.any { it.contains("not closed") })
        assertEquals("http://h", open.template.url)
    }

    @Test fun environmentVariablesAndSecrets() {
        val e = BrunoConverter.environment("dev", """
            vars {
              host: http://localhost
              ~off: x
              home: {{process.env.HOME}}
            }
            vars:secret [
              token,
              ~skip
              key
            ]
        """.trimIndent() + "\n")
        assertEquals("dev", e.environment.name)
        assertEquals(mapOf("host" to "http://localhost", "home" to "{{\$processEnv HOME}}"), e.environment.variables)
        assertEquals(listOf("token", "key"), e.secrets)
    }

    @Test fun writtenHttpFileParsesBack() {
        val a = convert("""
            meta {
              name: Login
              seq: 1
            }

            post {
              url: {{host}}/login
              body: json
            }

            assert {
              res.status: eq 200
            }

            body:json {
              {"u": "a"}
            }
        """).template
        val b = convert("""
            post {
              url: {{host}}/up
              body: multipartForm
            }

            body:multipart-form {
              t: x
              f: @file(p.json)
            }
        """).template
        val parsed = HttpFileParser().parse(HttpFileWriter.write(listOf(a, b), listOf("converted")))
        assertEquals(2, parsed.requests.size)
        val (first, second) = parsed.requests
        assertEquals("Login", first.name)
        assertEquals("POST", first.method)
        assertEquals(listOf("status == 200"), first.assertions)
        assertEquals(Body.Text("{\"u\": \"a\"}"), first.body)
        val multipart = second.body as Body.Multipart
        assertEquals(PartContent.Text("x"), multipart.parts[0].content)
        assertEquals(PartContent.File("p.json"), multipart.parts[1].content)
    }
}
