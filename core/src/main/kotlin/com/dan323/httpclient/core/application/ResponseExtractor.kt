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
 * - `headers.Name`: header value, case-insensitive
 * - `body.*`: the whole body
 * - `body.$.a.b[0]`: JSONPath (delegated to the [JsonExtractor] port)
 * - `body./a/b`, `body.//a[@id='1']`, `body.xpath:count(//a)`: XPath (JDK `javax.xml`)
 * - `body.regex:pattern`: first match of the regex; group 1 when the pattern has a group, else the whole match
 *
 * Returns null when the expression is unsupported, malformed or finds nothing.
 */
class ResponseExtractor(private val json: JsonExtractor) {

    fun extract(response: HttpResponse, expression: String): String? = when {
        expression == "status" -> response.statusCode.toString()
        expression == "body.*" -> response.bodyAsString()
        expression.startsWith("headers.") -> response.header(expression.removePrefix("headers."))
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

    fun xpath(xml: String, expression: String): String? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val compiled = XPathFactory.newInstance().newXPath().compile(expression)
        val nodes = runCatching { compiled.evaluate(doc, XPathConstants.NODESET) as NodeList }.getOrNull()
        if (nodes != null) {
            if (nodes.length == 0) null else nodes.item(0).textContent
        } else {
            compiled.evaluate(doc, XPathConstants.STRING) as String
        }
    }.getOrNull()
}
