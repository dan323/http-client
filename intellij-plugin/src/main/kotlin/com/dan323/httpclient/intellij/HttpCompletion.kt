package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.KnownVariable
import com.dan323.httpclient.viewmodel.CompletionContext
import com.dan323.httpclient.viewmodel.CompletionItem
import com.dan323.httpclient.viewmodel.analyzeFor
import com.dan323.httpclient.viewmodel.applyCompletion
import com.dan323.httpclient.viewmodel.completionContext
import com.dan323.httpclient.viewmodel.completions
import com.dan323.httpclient.viewmodel.editBetween
import com.dan323.httpclient.viewmodel.kindLabel
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import java.nio.file.Path

/**
 * Suggests the names usable in `{{ }}` at the caret: file variables above it, the chosen environment's variables,
 * the responses of earlier requests and the dynamic variables. What is suggested and what is inserted come from
 * `view-model` (shared with the desktop editor); this file only hands them to the IDE.
 */
class HttpCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(HttpLanguage), object : com.intellij.codeInsight.completion.CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: com.intellij.util.ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile
                val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
                val text = parameters.editor.document.text
                val ref = completionContext(text, parameters.offset) ?: return
                val service = file.project.getService(HttpClientService::class.java)
                val environments = if (path == null) emptyMap() else service.environmentVariableNames(Path.of(path))
                val known = analyzeFor(text, service.environmentChoice, environments).knownVariablesAt(parameters.offset)
                // The view-model already filtered and ordered the names (starts-with, then contains): accept all of them, keep its order.
                val everything = object : PrefixMatcher(ref.prefix) {
                    override fun prefixMatches(name: String) = true
                    override fun cloneWithPrefix(prefix: String) = this
                }
                val sorted = result.withPrefixMatcher(everything)
                completions(known, ref.prefix).forEachIndexed { i, item -> sorted.addElement(lookup(item, ref, i, known)) }
            }
        })
    }

    private fun lookup(item: CompletionItem, ref: CompletionContext, rank: Int, known: List<KnownVariable>): LookupElement =
        com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create(item.name).withTypeText(kindLabel(item.kind), true).withInsertHandler(Insert(item, ref)),
            (known.size - rank).toDouble(),
        )

    /**
     * The IDE has inserted the item's name over the typed prefix; this puts back the text as it was, lets
     * [applyCompletion] decide the result (the rest of the name after the caret, the closing braces, the caret) and
     * writes only what differs.
     */
    private class Insert(private val item: CompletionItem, private val ref: CompletionContext) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, element: LookupElement) {
            val document = context.document
            val now = document.text
            val typedEnd = context.startOffset + ref.prefix.length
            val before = now.substring(0, context.startOffset) + ref.prefix + now.substring(context.tailOffset)
            val original = completionContext(before, typedEnd) ?: return
            val done = applyCompletion(before, original, typedEnd, item)
            val edit = editBetween(now, done.text)
            document.replaceString(edit.start, edit.end, edit.replacement)
            context.editor.caretModel.moveToOffset(done.caret)
        }
    }
}

/** Opens the suggestions as soon as `{{` is typed. */
class HttpTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != HttpLanguage || charTyped != '{') return Result.CONTINUE
        val offset = editor.caretModel.offset
        if (offset == 0 || editor.document.charsSequence[offset - 1] != '{') return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
