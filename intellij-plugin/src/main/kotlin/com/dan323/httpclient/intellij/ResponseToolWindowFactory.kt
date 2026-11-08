package com.dan323.httpclient.intellij

import com.dan323.httpclient.viewmodel.JsonPart
import com.dan323.httpclient.viewmodel.RunControls
import com.dan323.httpclient.viewmodel.StatusClass
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.jsonSpans
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
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
            if (report.isEmpty) doc.add(emptyStateHint(), muted)
            report.message?.let { doc.add("$it\n", muted) }
            report.environment?.let { doc.add("Environment: $it\n\n", muted) }
            report.sections.forEach { doc.addSection(it) }
            pane.caretPosition = 0
        }
        render(service.lastReport)
        service.onReport(toolWindow.disposable) { r -> SwingUtilities.invokeLater { render(r) } }

        val reset = JButton("Reset chain", AllIcons.Actions.Refresh).apply {
            toolTipText = "Forget remembered responses (e.g. an expired login) so chained requests run again"
            addActionListener { service.resetChain() }
        }
        // Enabled only while a request runs (what the desktop app does too, see RunControls); the same action is in the title bar.
        val cancel = JButton(RunControls.CANCEL_LABEL, AllIcons.Actions.Suspend).apply {
            toolTipText = "Stop the running request; responses that already arrived stay"
            isEnabled = service.isRunning
            addActionListener { service.cancel() }
        }
        service.onRunning(toolWindow.disposable) { running -> SwingUtilities.invokeLater { cancel.isEnabled = RunControls(running).canCancel } }
        ActionManager.getInstance().getAction("httpclient.CancelRequest")?.let { toolWindow.setTitleActions(listOf(it)) }
        val top = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(reset); add(cancel) }, BorderLayout.WEST)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(pane), BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}

/** How to run a request. The shortcut is read from the keymap (it follows the user's binding) so it is written only in plugin.xml. */
private fun emptyStateHint(): String {
    val shortcut = KeymapUtil.getFirstKeyboardShortcutText("httpclient.RunRequest")
    val keys = if (shortcut.isEmpty()) "" else ", or press $shortcut"
    return "No request has run yet. In a .http file, click the green run icon in the gutter next to a request,\n" +
        "or right-click inside it and choose Run HTTP Request$keys.\n"
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
            add("  Error: ${d.message}\n", bad, bold = true)
            d.assertionsNotRunLabel?.let { add("  $it\n", muted) }
            add("\n")
        }
        is OutcomeDisplay.Response -> {
            add(d.summary + "\n", bold = true)
            val color = when (d.statusClass) { StatusClass.SUCCESS -> ok; StatusClass.REDIRECT -> redirect; else -> bad }
            add("  ${d.statusLabel}", color, bold = true)
            add("   ${d.durationLabel}\n", muted)
            d.notices.forEach { add("  ${it.text}\n", if (it.isError) bad else muted) }
            d.assertionSummary?.let { summary ->
                add("  $summary\n", if (d.hasFailedAssertions) bad else ok, bold = true)
                d.assertions.forEach { add("    ${it.label}\n", if (it.passed) ok else bad) }
            }
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
