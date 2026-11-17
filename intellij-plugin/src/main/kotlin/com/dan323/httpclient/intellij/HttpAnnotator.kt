package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.viewmodel.analyzeFor
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.nio.file.Path

/**
 * Underlines undefined variables, bad references, cycles and lines that do not parse (the same [com.dan323.httpclient.core.application.VariableAnalysis]
 * as the desktop app), under the environment chosen in the header. External because it reads the env file, which must
 * not happen in the highlighting pass on the UI thread.
 */
class HttpAnnotator : ExternalAnnotator<HttpAnnotator.Input, List<Diagnostic>>(), DumbAware {
    class Input(val file: Path, val text: String, val choice: EnvironmentChoice, val service: HttpClientService)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path ?: return null
        val service = file.project.getService(HttpClientService::class.java)
        return Input(Path.of(path), editor.document.text, service.environmentChoice, service)
    }

    override fun doAnnotate(input: Input): List<Diagnostic> =
        analyzeFor(input.text, input.choice, input.service.environmentVariableNames(input.file)).diagnostics

    override fun apply(file: PsiFile, result: List<Diagnostic>, holder: AnnotationHolder) {
        val length = file.textLength
        for (d in result) {
            val start = d.range.offset.coerceIn(0, length)
            val end = (d.range.offset + d.range.length).coerceIn(start, length)
            if (start == end) continue
            holder.newAnnotation(if (d.severity == Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING, d.message)
                .range(TextRange(start, end))
                .create()
        }
    }
}
