package com.dan323.httpclient.intellij

import com.dan323.httpclient.viewmodel.JsonPart
import com.dan323.httpclient.viewmodel.StatusClass
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.jsonSpans
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

private val ok = JBColor(Color(0x2E7D32), Color(0x6AAB73))
private val redirect = JBColor(Color(0xB26A00), Color(0xE0A030))
private val bad = JBColor(Color(0xC62828), Color(0xE06C6C))
private val muted = JBColor(Color(0x808080), Color(0x8A8A8A))
private val keyColor = JBColor(Color(0x871094), Color(0x9876AA))
private val stringColor = JBColor(Color(0x067D17), Color(0x6A8759))
private val numberColor = JBColor(Color(0x1750EB), Color(0x6897BB))
private val keywordColor = JBColor(Color(0x0033B3), Color(0xCC7832))

class ResponseToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(HttpClientService::class.java)
        val font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        val pane = JTextPane().apply { isEditable = false; this.font = font; border = BorderFactory.createEmptyBorder(6, 10, 6, 10) }

        fun render(report: Report) {
            val doc = pane.styledDocument
            doc.remove(0, doc.length)
            report.message?.let { doc.add("$it\n", muted) }
            report.environment?.let { doc.add("Environment: $it\n\n", muted) }
            report.sections.forEach { doc.addSection(it) }
            pane.caretPosition = 0
        }
        render(service.lastReport)
        service.onReport(toolWindow.disposable) { r -> SwingUtilities.invokeLater { render(r) } }

        // Environment picker: always visible, lists the env file next to the open .http file.
        val combo = JComboBox<String>()   // entries are matched by index into [items], never by label
        var items = environmentItems(emptyList(), service.environmentChoice)
        var reloading = false   // setting the model/selection programmatically fires the listener: ignore it
        fun selectedHttpFile() = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.extension == "http" }?.path
        // Env files are read on a pooled thread; the combo is updated on the EDT, and a result for a file that is
        // no longer the active one is discarded.
        fun reload() {
            val path = selectedHttpFile()
            ApplicationManager.getApplication().executeOnPooledThread {
                // null names = unknown (no .http file open, or the env file is unreadable): never shown as "(missing)".
                var names: List<String>? = null
                var hint: String? = if (path == null) "Open a .http file to list its environments" else null
                if (path != null) {
                    try { names = service.environments(Path.of(path)).map { e -> e.name } }
                    catch (e: EnvironmentFileException) { hint = e.message }
                }
                SwingUtilities.invokeLater {
                    if (selectedHttpFile() != path) return@invokeLater
                    reloading = true
                    try {
                        items = environmentItems(names, service.environmentChoice)
                        combo.model = DefaultComboBoxModel(items.map { it.label }.toTypedArray())
                        combo.selectedIndex = selectedIndex(items, service.environmentChoice)
                        val chosen = service.environmentChoice
                        combo.toolTipText = hint ?: if (names != null && chosen is EnvironmentChoice.Named && chosen.name !in names)
                            "'${chosen.name}' is not defined for this file; running will fail until you pick another" else null
                    } finally {
                        reloading = false
                    }
                }
            }
        }
        combo.addActionListener {
            if (reloading) return@addActionListener
            items.getOrNull(combo.selectedIndex)?.let { service.environmentChoice = it.choice }
        }
        combo.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent?) { reload() }
        })
        reload()
        // Keep the combo in step with the active file and with changes made through the popup action.
        project.messageBus.connect(toolWindow.disposable).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { SwingUtilities.invokeLater { reload() } }
            })
        service.onEnvironmentChoice(toolWindow.disposable) {
            SwingUtilities.invokeLater { if (items.getOrNull(combo.selectedIndex)?.choice != service.environmentChoice) reload() }
        }

        val reset = JButton("Reset chain").apply {
            toolTipText = "Forget remembered responses (e.g. an expired login) so chained requests run again"
            addActionListener { service.resetChain() }
        }
        val top = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JBLabel("Environment:"), BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)
            add(reset, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(pane), BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

private fun attrs(color: Color? = null, bold: Boolean = false) = SimpleAttributeSet().apply {
    color?.let { StyleConstants.setForeground(this, it) }
    StyleConstants.setBold(this, bold)
}

private fun StyledDocument.add(text: String, color: Color? = null, bold: Boolean = false) =
    insertString(length, text, attrs(color, bold))

private fun StyledDocument.addSection(s: Section) {
    when (val d = s.display) {
        is OutcomeDisplay.Reused -> {
            add(d.summary + "\n", bold = true)
            add("  reused earlier response (not sent again)\n\n", muted)
        }
        is OutcomeDisplay.Error -> {
            add(d.summary + "\n", bold = true)
            add("  Error: ${d.message}\n\n", bad, bold = true)
        }
        is OutcomeDisplay.Response -> {
            add(d.summary + "\n", bold = true)
            val color = when (d.statusClass) { StatusClass.SUCCESS -> ok; StatusClass.REDIRECT -> redirect; else -> bad }
            add("  ${d.statusLabel}", color, bold = true)
            add("   ${d.durationLabel}\n", muted)
            d.notices.forEach { add("  ${it.text}\n", if (it.isError) bad else muted) }
            d.headers.forEach { (k, v) -> add("  $k", keyColor); add(": ", muted); add("$v\n") }
            add("\n")
            addJson(d.prettyBody)
            add("\n\n")
        }
    }
}

private fun StyledDocument.addJson(body: String) {
    var last = 0
    for (span in jsonSpans(body)) {
        add(body.substring(last, span.start))
        val color = when (span.part) {
            JsonPart.KEY -> keyColor
            JsonPart.STRING -> stringColor
            JsonPart.NUMBER -> numberColor
            JsonPart.KEYWORD -> keywordColor
            JsonPart.PUNCT -> null
        }
        add(body.substring(span.start, span.end), color)
        last = span.end
    }
    add(body.substring(last))
}
