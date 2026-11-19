package com.dan323.httpclient.intellij

import com.dan323.httpclient.viewmodel.analyzeFor
import com.dan323.httpclient.viewmodel.declarationOf
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement

/**
 * Ctrl+click or Go to Declaration on `{{host}}` goes to its `@host = ...` line, and on `{{login.response...}}` to the
 * `# @name login` of that request. Environment and dynamic variables are not defined in the file, so they have no target.
 * Only the file is analysed here (no env file is read), because the answer does not depend on the environment.
 */
class HttpGotoDeclarationHandler : GotoDeclarationHandler, DumbAware {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile?.takeIf { it.language == HttpLanguage } ?: return null
        val text = editor?.document?.text ?: file.text
        val target = declarationOf(analyzeFor(text, com.dan323.httpclient.core.application.EnvironmentChoice.None, emptyMap()), offset) ?: return null
        return file.findElementAt(target.offset)?.let { arrayOf(it) }
    }

    override fun getActionText(context: DataContext): String? = null
}
