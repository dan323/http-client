package com.dan323.httpclient.intellij

import com.dan323.httpclient.viewmodel.EnvironmentPickerHost
import com.dan323.httpclient.viewmodel.EnvironmentPickerModel
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
 * and every open header follows it. All logic is in `view-model` ([EnvironmentPickerModel], [EnvironmentPickerHost],
 * unit tested); this file is Swing/IDE glue only and is not covered by tests (it needs an IDE).
 */
class EnvironmentEditorHeaderProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? =
        if (file.extension != "http" || !file.isInLocalFileSystem) null else Function { editor -> EnvironmentHeaderPanel(project, editor, file) }
}

private class EnvironmentHeaderPanel(project: Project, editor: FileEditor, file: VirtualFile) : EditorNotificationPanel(editor, Status.Info) {
    private val service = project.getService(HttpClientService::class.java)
    private val combo = JComboBox<String>()
    // A new model per time the panel is shown; its choice listener is removed when the panel is hidden or replaced.
    private val host = EnvironmentPickerHost {
        val scope = Disposer.newDisposable(editor, "EnvironmentHeaderPanel")
        EnvironmentPickerModel(
            { service.environments(Path.of(file.path)).map { it.name } }, { service.environmentChoice }, { service.environmentChoice = it },
            { listener -> service.onEnvironmentChoice(scope, listener); { Disposer.dispose(scope) } },
            { ApplicationManager.getApplication().executeOnPooledThread(it) }, { SwingUtilities.invokeLater(it) },
        ) { state ->
            combo.model = DefaultComboBoxModel(state.labels.toTypedArray())
            combo.selectedIndex = state.selected
            combo.toolTipText = state.hint
        }
    }

    init {
        text("Environment:")
        add(combo)
        combo.addActionListener { host.select(combo.selectedIndex) }
        // Re-read the env file whenever the list is about to open (mouse or keyboard).
        combo.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = host.reload()
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    override fun addNotify() { super.addNotify(); host.attach() }

    override fun removeNotify() { host.detach(); super.removeNotify() }
}
