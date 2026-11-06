package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

/**
 * Extracts a value from a response with one expression, shared by chaining (`{{name.response...}}`)
 * and assertions. Expression forms:
 *
 * - `status`: status code
 * - `headers.Name`: header value, case-insensitive; the first one when the header is repeated
 * - `headers.Name[2]`: the third value of a repeated header (0-based, e.g. `Set-Cookie`); missing when out of range
 * - `headers.Name[*]`: all values of the header joined with a newline (values may contain commas)
 * - `body.*`: the whole body, decoded with the charset of the Content-Type header (UTF-8 when absent or unknown)
 * - `body.$.a.b[0]`: JSONPath (delegated to the [JsonExtractor] port)
 * - `body./a/b`, `body.//a[@id='1']`, `body.xpath:count(//a)`: XPath (JDK `javax.xml`)
 * - `body.regex:pattern`: first match of the regex; group 1 when the pattern has a group, else the whole match
 *
 * XPath evaluation is hardened (secure processing, no DOCTYPE, no external access) and stops after
 * [XPATH_TIMEOUT_MILLIS]; a timed-out expression finds nothing.
 *
 * Returns null when the expression is unsupported, malformed or finds nothing.
 */
class ResponseExtractor(private val json: JsonExtractor) {

    fun extract(response: HttpResponse, expression: String): String? = when {
        expression == "status" -> response.statusCode.toString()
        expression == "body.*" -> response.bodyAsString()
        expression.startsWith("headers.") -> header(response, expression.removePrefix("headers."))
        expression.startsWith("body.") -> extractBody(response.bodyAsString(), expression.removePrefix("body."))
        else -> null
    }

    fun extractBody(body: String, expression: String): String? = when {
        expression.startsWith("regex:") -> regex(body, expression.removePrefix("regex:"))
        expression.startsWith("xpath:") -> xpath(body, expression.removePrefix("xpath:"))
        expression.startsWith("/") -> xpath(body, expression)
        else -> json.extract(body, expression)
    }

    fun regex(text: String, pattern: String): String? {
        val match = runCatching { Regex(pattern).find(RegexGuard(text)) }.getOrNull() ?: return null
        return if (match.groups.size > 1) match.groups[1]?.value else match.value
    }

    private fun header(response: HttpResponse, spec: String): String? {
        val m = INDEXED_HEADER.matchEntire(spec) ?: return response.header(spec)
        val values = response.headerValues(m.groupValues[1])
        val index = m.groupValues[2]
        return if (index == "*") values.takeIf { it.isNotEmpty() }?.joinToString("\n") else values.getOrNull(index.toInt())
    }

    fun xpath(xml: String, expression: String): String? {
        val task = java.util.concurrent.FutureTask<String?> { xpathUnbounded(xml, expression) }
        // XPath evaluation cannot be interrupted, so it runs on a daemon thread that is abandoned on timeout.
        Thread(task, "xpath-eval").apply { isDaemon = true }.start()
        return try {
            task.get(XPATH_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            null
        }
    }

    private fun xpathUnbounded(xml: String, expression: String): String? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val xpathFactory = XPathFactory.newInstance().apply {
            // also disables extension functions
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val compiled = xpathFactory.newXPath().compile(expression)
        val nodes = runCatching { compiled.evaluate(doc, XPathConstants.NODESET) as NodeList }.getOrNull()
        return if (nodes != null) {
            if (nodes.length == 0) null else nodes.item(0).textContent
        } else {
            compiled.evaluate(doc, XPathConstants.STRING) as String
        }
    }

    companion object {
        const val XPATH_TIMEOUT_MILLIS = 2_000L
        private val INDEXED_HEADER = Regex("(.+)\\[(\\d{1,6}|\\*)]")
    }
}
