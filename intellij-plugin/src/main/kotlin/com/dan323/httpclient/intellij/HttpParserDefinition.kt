package com.dan323.httpclient.intellij

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class HttpPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HttpLanguage) {
    override fun getFileType(): FileType = HttpFileType.INSTANCE
}

/** Flat PSI: the file is just a sequence of leaf tokens. Structure comes from [com.dan323.httpclient.core]. */
class HttpParserDefinition : ParserDefinition {
    private val file = IFileElementType(HttpLanguage)

    override fun createLexer(project: Project?): Lexer = HttpLexer()
    override fun createParser(project: Project?) = PsiParser { root: IElementType, builder: PsiBuilder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }
    override fun getFileNodeType() = file
    override fun getWhitespaceTokens() = TokenSet.create(TokenType.WHITE_SPACE)
    override fun getCommentTokens() = TokenSet.create(HttpTokens.COMMENT)
    override fun getStringLiteralElements() = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = throw UnsupportedOperationException("flat PSI: ${node.elementType}")
    override fun createFile(viewProvider: FileViewProvider): PsiFile = HttpPsiFile(viewProvider)
}
