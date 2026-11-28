package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.viewmodel.AllowlistForm
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | HTTP Client: Allowed Access. The checks are [AllowlistForm]'s, shared with the desktop app. */
class AllowlistConfigurable : Configurable {
    private var envArea: JBTextArea? = null
    private var rootsArea: JBTextArea? = null

    private val settings get() = AllowlistSettings.getInstance()

    override fun getDisplayName() = AllowlistSettings.DISPLAY_NAME

    override fun createComponent(): JComponent {
        val env = JBTextArea(5, 40).also { envArea = it }
        val roots = JBTextArea(5, 40).also { rootsArea = it }
        reset()
        return FormBuilder.createFormBuilder()
            .addComponent(comment("A .http file reads nothing outside its folder and no OS variable unless you allow it here. Allow only what you trust every file you open to read. Kept for this IDE only, never in a project."))
            .addLabeledComponent(JBLabel("Allowed environment variables (one name per line; {{\$env.NAME}} reads only these):"), JBScrollPane(env), true)
            .addLabeledComponent(JBLabel("Allowed folders (one absolute folder per line, ~ for your home folder; for < path bodies and >> path saves):"), JBScrollPane(roots), true)
            .addComponent(comment("Also read at IDE start: -Dhttpclient.allowEnv / HTTPC_ALLOW_ENV and -Dhttpclient.allowFileRoots / HTTPC_ALLOW_FILE_ROOTS."))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun comment(text: String) = JBLabel("<html>$text</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    override fun isModified(): Boolean {
        val env = envArea ?: return false
        val roots = rootsArea ?: return false
        return AllowlistForm.parse(env.text, roots.text) != settings.allowlists
    }

    override fun apply() {
        val env = envArea ?: return
        val roots = rootsArea ?: return
        when (val checked = AllowlistForm.check(env.text, roots.text, FileAccessPolicy::rootProblem)) {
            is AllowlistForm.Checked.Invalid -> throw ConfigurationException(checked.errors.joinToString("\n"), "Cannot Save the Allowed Access")
            is AllowlistForm.Checked.Valid -> {
                settings.update(checked.allowlists)
                reset()   // show the saved form: trimmed, without blank lines and repeats
            }
        }
    }

    override fun reset() {
        val saved = settings.allowlists
        envArea?.text = AllowlistForm.text(saved.envNames)
        rootsArea?.text = AllowlistForm.text(saved.fileRoots)
    }

    override fun disposeUIResources() {
        envArea = null
        rootsArea = null
    }
}
