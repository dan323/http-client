package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.DiagnosticCodes
import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.core.port.out.EnvironmentFileException
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
 * not happen in the highlighting pass on the UI thread. [HttpClientService] restarts the highlighting when the choice
 * changes, and a result computed for another choice is dropped in [apply].
 */
class HttpAnnotator : ExternalAnnotator<HttpAnnotator.Input, HttpAnnotator.Result>(), DumbAware {
    class Input(val file: Path, val text: String, val choice: EnvironmentChoice, val service: HttpClientService)

    /** [envError] is set when the environment file could not be read: its variables are then unknown, not undefined. */
    class Result(val choice: EnvironmentChoice, val diagnostics: List<Diagnostic>, val envError: String?)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path ?: return null
        val service = file.project.getService(HttpClientService::class.java)
        return Input(Path.of(path), editor.document.text, service.environmentChoice, service)
    }

    override fun doAnnotate(input: Input): Result {
        val (names, error) = try {
            input.service.environmentVariableNames(input.file) to null
        } catch (e: EnvironmentFileException) {
            emptyMap<String, Set<String>>() to e.message
        }
        val found = analyzeFor(input.text, input.choice, names).diagnostics
        // With an unreadable env file nothing is known about its variables, so none is called undefined.
        return Result(input.choice, if (error != null) found.filter { it.code != DiagnosticCodes.UNDEFINED_VARIABLE } else found, error)
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        if (file.project.getService(HttpClientService::class.java).environmentChoice != result.choice) return   // stale: the choice changed meanwhile
        val length = file.textLength
        result.envError?.let { message ->
            if (length > 0) holder.newAnnotation(HighlightSeverity.WARNING, "Environment file: $message").range(TextRange(0, minOf(length, 1))).fileLevel().create()
        }
        for (d in result.diagnostics) {
            val start = d.range.offset.coerceIn(0, length)
            val end = (d.range.offset + d.range.length).coerceIn(start, length)
            if (start == end) continue
            holder.newAnnotation(if (d.severity == Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING, d.message)
                .range(TextRange(start, end))
                .create()
        }
    }
}
