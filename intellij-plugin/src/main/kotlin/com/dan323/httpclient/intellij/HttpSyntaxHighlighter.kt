package com.dan323.httpclient.intellij

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class HttpSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = HttpLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = when (type) {
        HttpTokens.COMMENT -> COMMENT
        HttpTokens.SEPARATOR -> SEPARATOR
        HttpTokens.METHOD -> METHOD
        HttpTokens.URL -> URL
        HttpTokens.HEADER_NAME -> HEADER_NAME
        HttpTokens.VARIABLE -> VARIABLE
        HttpTokens.FILE_REF -> FILE_REF
        else -> emptyArray()
    }

    private companion object {
        val COMMENT = arrayOf(createTextAttributesKey("HTTPCLIENT_COMMENT", Colors.LINE_COMMENT))
        val SEPARATOR = arrayOf(createTextAttributesKey("HTTPCLIENT_SEPARATOR", Colors.BLOCK_COMMENT))
        val METHOD = arrayOf(createTextAttributesKey("HTTPCLIENT_METHOD", Colors.KEYWORD))
        val URL = arrayOf(createTextAttributesKey("HTTPCLIENT_URL", Colors.STRING))
        val HEADER_NAME = arrayOf(createTextAttributesKey("HTTPCLIENT_HEADER_NAME", Colors.INSTANCE_FIELD))
        val VARIABLE = arrayOf(createTextAttributesKey("HTTPCLIENT_VARIABLE", Colors.TEMPLATE_LANGUAGE_COLOR))
        val FILE_REF = arrayOf(createTextAttributesKey("HTTPCLIENT_FILE_REF", Colors.METADATA))
    }
}

class HttpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = HttpSyntaxHighlighter()
}
