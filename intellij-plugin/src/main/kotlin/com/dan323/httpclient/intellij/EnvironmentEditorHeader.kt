package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.function.Function
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Puts the environment picker in the header of every `.http` editor, like JetBrains' HTTP Client. The choice lives in
 * [HttpClientService] (per project, until the IDE restarts), so picking it in one file applies to runs from all files
 * and every open header follows it.
 */
class EnvironmentEditorHeaderProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "http" || !file.isInLocalFileSystem) return null
        return Function { editor -> EnvironmentHeaderPanel(project, editor, file) }
    }
}

private class EnvironmentHeaderPanel(project: Project, private val editor: FileEditor, private val file: VirtualFile) :
    EditorNotificationPanel(editor, Status.Info) {
    private val service = project.getService(HttpClientService::class.java)
    private val combo = JComboBox<String>()   // entries are matched by index into [items], never by label
    private var items = environmentItems(emptyList(), service.environmentChoice)
    private var reloading = false             // setting the model/selection programmatically fires the listener: ignore it
    private var disposed = false
    private var listenerScope: Disposable? = null

    init {
        text("Environment:")
        add(combo)
        combo.addActionListener {
            if (reloading) return@addActionListener
            items.getOrNull(combo.selectedIndex)?.let { service.environmentChoice = it.choice }
        }
        // Re-read the env file when the list is opened, so an edited http-client.env.json shows up without reopening.
        combo.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) { reload() }
        })
        Disposer.register(editor) { disposed = true }
        reload()
    }

    // The choice listener lives exactly as long as this panel is shown: a superseded panel is removed from the editor,
    // so it stops listening (and reloading) instead of lingering until the editor closes.
    override fun addNotify() {
        super.addNotify()
        listenerScope?.let { Disposer.dispose(it) }
        val scope = Disposer.newDisposable(editor, "EnvironmentHeaderPanel")
        listenerScope = scope
        disposed = false
        service.onEnvironmentChoice(scope) {
            SwingUtilities.invokeLater { if (items.getOrNull(combo.selectedIndex)?.choice != service.environmentChoice) reload() }
        }
    }

    override fun removeNotify() {
        disposed = true
        listenerScope?.let { Disposer.dispose(it) }
        listenerScope = null
        super.removeNotify()
    }

    /** Env files are read on a pooled thread and the combo is updated on the EDT. */
    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            // null names = unknown (unreadable env file): never shown as "(missing)".
            var names: List<String>? = null
            var hint: String? = null
            try { names = service.environments(Path.of(file.path)).map { it.name } }
            catch (e: EnvironmentFileException) { hint = e.message }
            // Anything else (e.g. InvalidPathException): keep the combo usable with the (none) entry and show why.
            catch (e: Exception) { hint = "Cannot read environments: ${e.message ?: e.javaClass.simpleName}" }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                reloading = true
                try {
                    val chosen = service.environmentChoice
                    items = environmentItems(names, chosen)
                    combo.model = DefaultComboBoxModel(items.map { it.label }.toTypedArray())
                    combo.selectedIndex = selectedIndex(items, chosen)
                    combo.toolTipText = environmentHint(names, chosen, hint)
                } finally {
                    reloading = false
                }
            }
        }
    }
}
