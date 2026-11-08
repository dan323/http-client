package com.dan323.httpclient.intellij

import com.dan323.httpclient.viewmodel.EnvironmentPickerModel
import com.dan323.httpclient.viewmodel.EnvironmentPickerState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.nio.file.Path
import java.util.function.Function
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Puts the environment picker in the header of every `.http` editor, like JetBrains' HTTP Client. The choice lives in
 * [HttpClientService] (per project, until the IDE restarts), so picking it in one file applies to runs from all files
 * and every open header follows it. The logic is [EnvironmentPickerModel] (in `view-model`, unit tested); this is the
 * Swing glue only.
 */
class EnvironmentEditorHeaderProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "http" || !file.isInLocalFileSystem) return null
        return Function { editor -> EnvironmentHeaderPanel(project, editor, file) }
    }
}

private class EnvironmentHeaderPanel(private val project: Project, private val editor: FileEditor, private val file: VirtualFile) :
    EditorNotificationPanel(editor, Status.Info) {
    private val service = project.getService(HttpClientService::class.java)
    private val combo = JComboBox<String>()
    private var model: EnvironmentPickerModel? = null
    private var scope: Disposable? = null

    init {
        text("Environment:")
        add(combo)
        combo.addActionListener { model?.select(combo.selectedIndex) }
        // Re-read the env file whenever the list is about to open (mouse or keyboard), so an edited
        // http-client.env.json shows up without reopening the file.
        combo.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) { model?.reload() }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })
    }

    // The choice listener lives exactly as long as this panel is shown: a superseded panel is removed from the editor,
    // so it stops listening (and reloading) instead of lingering until the editor closes.
    override fun addNotify() {
        super.addNotify()
        stop()
        val s = Disposer.newDisposable(editor, "EnvironmentHeaderPanel")
        scope = s
        model = EnvironmentPickerModel(
            loadNames = { service.environments(Path.of(file.path)).map { it.name } },
            getChoice = { service.environmentChoice },
            setChoice = { service.environmentChoice = it },
            // Unregistered when [s] is disposed (by stop()), so the returned remover has nothing left to do.
            subscribe = { listener -> service.onEnvironmentChoice(s, listener); {} },
            background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
            ui = { SwingUtilities.invokeLater(it) },
            render = ::show,
        ).also { it.start() }
    }

    override fun removeNotify() {
        stop()
        super.removeNotify()
    }

    private fun stop() {
        model?.dispose()
        model = null
        scope?.let { Disposer.dispose(it) }
        scope = null
    }

    private fun show(state: EnvironmentPickerState) {
        combo.model = DefaultComboBoxModel(state.items.map { it.label }.toTypedArray())
        combo.selectedIndex = state.selected
        combo.toolTipText = state.hint
    }
}
