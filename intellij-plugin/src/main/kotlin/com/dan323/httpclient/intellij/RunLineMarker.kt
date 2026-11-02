package com.dan323.httpclient.intellij

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiWhiteSpace

/** Run icon in the gutter on the first token of each request line. */
class RunLineMarker : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val type = element.node?.elementType
        val isStart = type == HttpTokens.METHOD ||
            (type == HttpTokens.URL && PsiTreeUtil.skipWhitespacesBackward(element)?.node?.elementType != HttpTokens.METHOD &&
                element.prevLeafOnLineIsBlank())
        if (!isStart) return null
        val offset = element.textRange.startOffset
        val file = element.containingFile
        val action = object : AnAction("Run HTTP Request", null, AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                val vfile = file.virtualFile ?: file.originalFile.virtualFile ?: return
                val text = file.text
                runRequest(file.project, vfile, text, text.take(offset).count { it == '\n' })
            }
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, arrayOf(action)) { "Run HTTP Request" }
    }

    private fun PsiElement.prevLeafOnLineIsBlank(): Boolean {
        val prev = prevSibling ?: return true
        return prev is PsiWhiteSpace && (prev.prevSibling == null || prev.text.contains('\n') || prev.prevSibling.node.elementType != HttpTokens.METHOD)
    }
}
