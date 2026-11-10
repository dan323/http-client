package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import java.io.ByteArrayOutputStream

/** A part with its variables already resolved and its file already read. */
class EncodedPart(val headers: List<Header>, val content: ByteArray)

/** Builds the bytes of a `multipart/...` body. Only the structure uses CRLF; part content is written exactly as given. */
internal object MultipartEncoder {
    fun encode(boundary: String, parts: List<EncodedPart>): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(text: String = "") { out.write(text.toByteArray()); out.write(CRLF) }
        for (part in parts) {
            line("--$boundary")
            part.headers.forEach { line("${it.name}: ${it.value}") }
            line()
            out.write(part.content)
            out.write(CRLF)
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    private val CRLF = "\r\n".toByteArray()
}
