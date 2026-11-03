package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.HttpLineLexer
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/** Run icon in the gutter on the first token of each request line (with or without a method). */
class RunLineMarker : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null) return null // leaves only
        val file = element.containingFile ?: return null
        val text = file.text
        val offset = element.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        // Only the first non-blank token of a line can carry the icon.
        if (text.substring(lineStart, offset).isNotBlank()) return null
        val line = text.take(offset).count { it == '\n' }
        if (line !in requestLines(file)) return null

        val action = object : AnAction("Run HTTP Request", null, AllIcons.RunConfigurations.TestState.Run) {
            override fun actionPerformed(e: AnActionEvent) {
                val vfile = file.virtualFile ?: file.originalFile.virtualFile ?: return
                runRequest(file.project, vfile, file.text, line)
            }
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, arrayOf(action)) { "Run HTTP Request" }
    }

    private fun requestLines(file: PsiFile): Set<Int> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(HttpLineLexer.requestLines(file.text), file)
    }
}
