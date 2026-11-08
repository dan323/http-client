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
import com.dan323.httpclient.viewmodel.environmentItems
import com.dan323.httpclient.viewmodel.selectedItem
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path

private fun AnActionEvent.isHttpFile() = getData(CommonDataKeys.VIRTUAL_FILE)?.extension == "http"

/** Saves open files, shows the response window and runs the request at [line] off the UI thread. Call on the EDT. */
fun runRequest(project: Project, vfile: VirtualFile, line: Int) {
    // The run uses the same text for the gutter icon and the action: the document as just saved (never PSI text).
    val documents = FileDocumentManager.getInstance()
    val document = documents.getDocument(vfile)
    documents.saveAllDocuments()
    val text = document?.text ?: String(vfile.contentsToByteArray(), vfile.charset)
    val toolWindows = ToolWindowManager.getInstance(project)
    toolWindows.getToolWindow("HTTP Response")?.show()
    ApplicationManager.getApplication().executeOnPooledThread {
        val started = project.getService(HttpClientService::class.java).run(Path.of(vfile.path), text, line)
        if (!started) ApplicationManager.getApplication().invokeLater({
            toolWindows.notifyByBalloon("HTTP Response", MessageType.INFO, "A request is already running; wait for it to finish.")
        }, project.disposed)
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
        runRequest(project, vfile, editor.document.getLineNumber(editor.caretModel.offset))
    }
}

/** Stops the running request. Enabled only while one runs; lives in the HTTP Response tool window and in Find Action. */
class CancelRequestAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && project.getService(HttpClientService::class.java).isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.getService(HttpClientService::class.java)?.cancel()
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
        var error: String? = null
        val names = try { service.environments(Path.of(vfile.path)).map { it.name } } catch (e: EnvironmentFileException) { error = e.message; null }
        val items = environmentItems(names, current)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setTitle("HTTP Environment (current: ${selectedItem(items, current).label})" + (error?.let { " - $it" } ?: ""))
            .setItemChosenCallback { service.environmentChoice = it.choice }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}
