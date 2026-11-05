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
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
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
        val current = service.environmentChoice
        // null (not empty) when the env file cannot be read: the current choice is then not reported as missing.
        val names = try { service.environments(Path.of(vfile.path)).map { it.name } } catch (_: EnvironmentFileException) { null }
        val items = environmentItems(names, current)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setTitle("HTTP Environment (current: ${selectedItem(items, current).label})")
            .setItemChosenCallback { service.environmentChoice = it.choice }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}
