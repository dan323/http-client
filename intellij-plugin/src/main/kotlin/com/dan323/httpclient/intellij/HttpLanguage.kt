package com.dan323.httpclient.intellij

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType

// Distinct ids so this plugin can coexist with IntelliJ Ultimate's bundled "HTTP" language.
object HttpLanguage : Language("HttpClientFile")

class HttpTokenType(debugName: String) : IElementType(debugName, HttpLanguage)

object HttpTokens {
    val SEPARATOR = HttpTokenType("SEPARATOR")
    val COMMENT = HttpTokenType("COMMENT")
    val METHOD = HttpTokenType("METHOD")
    val URL = HttpTokenType("URL")
    val HEADER_NAME = HttpTokenType("HEADER_NAME")
    val COLON = HttpTokenType("COLON")
    val HEADER_VALUE = HttpTokenType("HEADER_VALUE")
    val BODY_TEXT = HttpTokenType("BODY_TEXT")
    val FILE_REF = HttpTokenType("FILE_REF")
    val VARIABLE = HttpTokenType("VARIABLE")
}
