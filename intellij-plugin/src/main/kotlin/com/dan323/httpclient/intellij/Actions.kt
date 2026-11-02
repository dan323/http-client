package com.dan323.httpclient.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path

private fun AnActionEvent.isHttpFile() = getData(CommonDataKeys.VIRTUAL_FILE)?.extension == "http"

/** Saves open files, shows the response window and runs the request at [line] off the UI thread. */
fun runRequest(project: Project, vfile: VirtualFile, text: String, line: Int) {
    ToolWindowManager.getInstance(project).getToolWindow("HTTP Response")?.show()
    ApplicationManager.getApplication().executeOnPooledThread {
        project.getService(HttpClientService::class.java).run(Path.of(vfile.path), text, line)
    }
}

class RunRequestAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.isHttpFile()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        runRequest(project, vfile, editor.document.text, editor.document.getLineNumber(editor.caretModel.offset))
    }
}

class SelectEnvironmentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.isHttpFile()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val service = project.getService(HttpClientService::class.java)
        val names = listOf("(none)") + service.environments(Path.of(vfile.path).parent).map { it.name }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("HTTP Environment (current: ${service.environmentName ?: "none"})")
            .setItemChosenCallback { service.environmentName = it.takeIf { n -> n != "(none)" } }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}
