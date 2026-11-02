package com.dan323.httpclient.intellij

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingUtilities

class ResponseToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(HttpClientService::class.java)
        val area = JBTextArea(service.lastOutput).apply {
            isEditable = false
            lineWrap = false
            font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        }
        service.onOutput { text ->
            SwingUtilities.invokeLater { area.text = text; area.caretPosition = 0 }
        }
        val content = ContentFactory.getInstance().createContent(JBScrollPane(area), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
