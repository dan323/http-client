package com.dan323.httpclient.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.viewmodel.CompletionItem
import com.dan323.httpclient.viewmodel.applyCompletion
import com.dan323.httpclient.viewmodel.completionContext
import com.dan323.httpclient.viewmodel.completions
import com.dan323.httpclient.viewmodel.kindLabel
import com.dan323.httpclient.viewmodel.findMatches
import com.dan323.httpclient.viewmodel.matchAfterReplacement
import com.dan323.httpclient.viewmodel.nextMatchIndex
import com.dan323.httpclient.viewmodel.previousMatchIndex
import com.dan323.httpclient.viewmodel.replaceAll
import com.dan323.httpclient.viewmodel.replaceMatch
import com.dan323.httpclient.viewmodel.replacedLabel
import com.dan323.httpclient.viewmodel.problemLabel
import com.dan323.httpclient.viewmodel.problemSummary
import com.dan323.httpclient.viewmodel.RunControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** Tight horizontal padding so the three buttons under the request list fit the 300 dp column. */
internal val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

/** How long the transient notice (e.g. "Chain reset") stays visible. */
private const val NOTICE_MILLIS = 4_000L

internal val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun RowScope.App(state: AppState, parent: Frame? = null) {
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    // Far left: the folder view, when shown.
    if (state.folderOpen) {
        FolderPanel(state, pickFolder = { state.pick { chooseFolder(parent) } }, modifier = Modifier.width(220.dp).fillMaxHeight().padding(12.dp))
        VerticalDivider()
    }

    // Left: file, environment and request list.
    Column(Modifier.width(300.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.running, onClick = { state.pick { chooseFile(parent, "Open .http file", save = false) }?.let(state::open) }) { Text("Open…") }
            OutlinedButton(enabled = !state.running, onClick = { state.pick { chooseFile(parent, "New .http file", save = true) }?.let(state::create) }) { Text("New…") }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { state.showFolder(!state.folderOpen) }, modifier = Modifier.testTag("folder-toggle")) { Text("Folder") }
        }
        if (state.file == null) Text("No file open", fontWeight = FontWeight.Bold)   // with a file open its tab shows the name
        RecentFilesMenu(state)
        EnvironmentPicker(state)
        OutlinedButton(
            enabled = state.file != null,
            contentPadding = CompactButtonPadding,
            onClick = { val show = state.envEditor == null; scope.launch(Dispatchers.IO) { state.showEnvironmentEditor(show) } },
            modifier = Modifier.testTag("env-edit-toggle"),
        ) { Text(when { state.envEditor == null -> "Edit environments"; state.envEditor?.dirty == true -> "Discard and close editor"; else -> "Close environment editor" }, maxLines = 1) }
        HorizontalDivider()
        state.parseError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(state.requests) { i, r ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (i == state.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { state.selected = i }
                        .padding(8.dp),
                ) {
                    Text(r.name ?: "Request ${i + 1}", fontWeight = FontWeight.SemiBold)
                    Text(requestSummary(r.method, r.url, if (dark) HighlightPalette.Dark else HighlightPalette.Light), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        val controls = state.controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = controls.canSend, contentPadding = CompactButtonPadding, onClick = { scope.launch(Dispatchers.IO) { state.run(all = false) } }) {
                Text(controls.sendLabel, maxLines = 1)
            }
            // Stops the request in flight; what already finished stays and the cancelled request is not remembered.
            OutlinedButton(enabled = controls.canCancel, contentPadding = CompactButtonPadding, onClick = { state.cancel() }) {
                Text(RunControls.CANCEL_LABEL, maxLines = 1)
            }
            OutlinedButton(enabled = controls.canSend, contentPadding = CompactButtonPadding, onClick = { scope.launch(Dispatchers.IO) { state.run(all = true) } }) {
                Text("Send all", maxLines = 1)
            }
        }
        // Responses of earlier requests (e.g. a login token) are remembered between runs; this forgets them.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = controls.canSend, contentPadding = CompactButtonPadding, onClick = { state.resetChain() }) {
                Text("Reset chain", maxLines = 1)
            }
            if (state.selectedHasLink) {
                OutlinedButton(
                    enabled = state.canOpenLinked,
                    contentPadding = CompactButtonPadding,
                    onClick = { scope.launch(Dispatchers.IO) { state.showLinkedResponse() } },
                    modifier = Modifier.testTag("open-linked"),
                ) { Text("Linked response", maxLines = 1) }
                if (state.dirty) Text("Save to look up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            // While a request runs the spinner sits next to the buttons; otherwise the short confirmation of Reset chain.
            if (state.running) {
                CircularProgressIndicator(Modifier.size(20.dp).testTag("run-progress"), strokeWidth = 2.dp)
            } else {
                val notice = state.notice
                if (notice != null) {
                    LaunchedEffect(state.noticeId) { delay(NOTICE_MILLIS); state.clearNotice() }
                    Text(notice, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }

    // Middle: tabs and editor.
    Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp)) {
        if (state.tabs.isNotEmpty()) TabBar(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.file != null && state.dirty && !state.running, onClick = { state.save() }) { Text("Save") }
            OutlinedButton(enabled = state.file != null, onClick = { if (state.find.open) state.find.open = false else state.find.show() }, modifier = Modifier.testTag("find-toggle")) { Text("Find") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        val problems = state.problems
        // The field keeps its own caret; the text itself belongs to [state] (an edit it refuses, or a reload, wins).
        var field by remember(state.file) { mutableStateOf(TextFieldValue(state.text)) }
        val shown = if (field.text == state.text) field else TextFieldValue(state.text, TextRange(field.selection.end.coerceAtMost(state.text.length)))
        val caret = shown.selection.end
        val context = completionContext(shown.text, caret).takeIf { shown.selection.collapsed && state.file != null && !state.running }
        var dismissed by remember(state.file) { mutableStateOf<Pair<String, Int>?>(null) }   // Esc hides the list until the text or caret moves
        val items = if (context == null || dismissed == shown.text to caret) emptyList() else completions(state.knownVariablesAt(caret), context.prefix)
        var picked by remember(items) { mutableStateOf(0) }
        // Enter only accepts once the user has moved through the list: otherwise it is a line break (`{{base` + Enter must not become `basket`).
        var navigated by remember(items) { mutableStateOf(false) }
        val editorFocus = remember { FocusRequester() }
        val chosen = picked.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        fun accept(item: CompletionItem) {
            val done = applyCompletion(shown.text, context ?: return, caret, item)
            if (!state.edit(done.text)) return   // refused (a request or a file switch is in progress): the text stays as it is
            field = TextFieldValue(done.text, TextRange(done.caret))
            editorFocus.requestFocus()   // a click on a suggestion must not take the keyboard away from the editor
        }
        val find = state.find
        val matches = find.matches(shown.text)
        val selection = shown.selection
        val currentMatch = matches.firstOrNull { it.first == selection.min && it.last + 1 == selection.max }
        fun select(index: Int) {
            val m = matches.getOrNull(index) ?: return
            field = TextFieldValue(shown.text, TextRange(m.first, m.last + 1))   // the field scrolls to it
        }
        fun step(forward: Boolean) {
            if (matches.isEmpty()) return
            select(if (forward) nextMatchIndex(matches, selection.max) else previousMatchIndex(matches, selection.min))
        }
        fun replaceOne() {
            val m = currentMatch
            if (m == null) { step(true); return }   // nothing selected yet: go to the next match first
            if (state.running || state.file == null) return
            val done = replaceMatch(shown.text, m, find.replacement)
            if (done.text != shown.text && !state.edit(done.text)) return   // refused: nothing changed
            field = TextFieldValue(done.text, TextRange(done.caret))
            find.clearStatus()
            val after = findMatches(done.text, find.query, find.matchCase)
            val next = matchAfterReplacement(after, m.first, done.caret)?.let { after[it] }
            if (next != null) field = TextFieldValue(done.text, TextRange(next.first, next.last + 1))
        }
        fun replaceEverything() {
            if (state.running || state.file == null) return
            val done = replaceAll(shown.text, find.query, find.replacement, find.matchCase)
            if (done.count > 0 && !state.edit(done.text)) {
                find.report("Not replaced: the file is busy.", shown.text)
                return
            }
            if (done.count > 0) field = TextFieldValue(done.text, TextRange(selection.end.coerceAtMost(done.text.length)))
            find.report(replacedLabel(done.count), if (done.count > 0) done.text else shown.text)
        }
        fun closeFind() { find.open = false; editorFocus.requestFocus() }
        if (find.open) {
            FindBar(
                find, matches.size, find.statusFor(shown.text), matches.indexOf(currentMatch).takeIf { it >= 0 }, canEdit = !state.running && state.file != null,
                onStep = ::step, onReplace = ::replaceOne, onReplaceAll = ::replaceEverything, onClose = ::closeFind,
            )
        }
        OutlinedTextField(
            value = shown,
            onValueChange = { value ->
                field = value
                if (value.text != state.text) state.edit(value.text)
            },
            enabled = state.file != null,
            readOnly = state.running,
            textStyle = mono,
            visualTransformation = remember(dark, problems, matches, currentMatch) { HttpVisualTransformation(if (dark) HighlightPalette.Dark else HighlightPalette.Light, problems, matches, currentMatch) },
            placeholder = { Text("Open or create a .http file") },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).testTag("editor").focusRequester(editorFocus).onPreviewKeyEvent { e ->
                val plain = !e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed
                if (e.type == KeyEventType.KeyDown && e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed) && state.file != null) { find.show(); true }
                else if (items.isEmpty() || e.type != KeyEventType.KeyDown) false
                else when {
                    e.key == Key.DirectionDown -> { picked = (chosen + 1) % items.size; navigated = true; true }
                    e.key == Key.DirectionUp -> { picked = (chosen - 1 + items.size) % items.size; navigated = true; true }
                    e.key == Key.Tab && plain -> { accept(items[chosen]); true }
                    (e.key == Key.Enter || e.key == Key.NumPadEnter) && plain && navigated -> { accept(items[chosen]); true }
                    e.key == Key.Escape -> { dismissed = shown.text to caret; true }
                    else -> false
                }
            },
        )
        Suggestions(items, chosen, onPick = ::accept)
        ProblemsList(problems)
    }

    // Right: responses.
    Column(Modifier.weight(1.2f).fillMaxHeight().padding(12.dp)) {
        val envEditor = state.envEditor
        if (envEditor != null) {
            EnvironmentPanel(state, envEditor, Modifier.fillMaxSize())
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.historyOpen) "History" else "Response", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (state.canRecordHistory) {
                Checkbox(checked = state.recordHistory, onCheckedChange = { state.recordHistory = it }, modifier = Modifier.testTag("record-history"))
                Text("Record history", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    contentPadding = CompactButtonPadding,
                    onClick = { val show = !state.historyOpen; scope.launch(Dispatchers.IO) { state.showHistory(show) } },
                    modifier = Modifier.testTag("history-toggle"),
                ) { Text(if (state.historyOpen) "Back to response" else "History", maxLines = 1) }
            }
        }
        if (state.canRecordHistory && state.recordHistory) {
            Text(
                "Stores raw responses in .http-history next to the file; keep it out of version control.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (state.historyOpen) {
            HistoryPanel(state, Modifier.fillMaxSize())
            return@Column
        }
        val cancelled = state.cancelledLabel
        if (cancelled != null) {
            // A cancelled run is an outcome, not an error: neutral colour, and the results that finished stay below.
            Text(cancelled, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        } else if (state.outcomes.isEmpty()) {
            Text(
                if (state.runningHere) "Sending…" else "Select a request and press Send (Ctrl/Cmd+Enter).",
                color = MaterialTheme.colorScheme.outline,
            )
        }
        ResponsePane(state.outcomes, Modifier.fillMaxSize())
    }
}

/** The names that fit what is typed inside `{{ }}`: click one, or use the arrow keys with Enter or Tab; Esc hides the list. */
@Composable
private fun Suggestions(items: List<CompletionItem>, chosen: Int, onPick: (CompletionItem) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState()).testTag("suggestions")) {
        items.forEachIndexed { i, item ->
            Row(
                Modifier.fillMaxWidth().background(if (i == chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .pointerInput(item) { detectTapGestures { onPick(item) } }   // not `clickable`: that would take the focus from the editor
                    .padding(horizontal = 8.dp, vertical = 2.dp).testTag("suggestion-$i"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(kindLabel(item.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** The problems found in the open file, one line each (`Line 3: ...`), with a count; nothing when the file is fine. */
@Composable
private fun ProblemsList(problems: List<Diagnostic>) {
    val summary = problemSummary(problems) ?: return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("problems")) {
        Text(summary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Column(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
            problems.forEach {
                Text(
                    problemLabel(it), style = MaterialTheme.typography.bodySmall,
                    color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** One tab per open file: its name (a bullet when it has unsaved edits) and a close button; the active tab is highlighted. */
@Composable
private fun TabBar(state: AppState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        state.tabs.forEachIndexed { i, tab ->
            val selected = i == state.activeTab
            Row(
                Modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { state.switchTo(i) }.padding(start = 10.dp).testTag("tab-$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tab.label + if (tab.dirty) " •" else "", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                Text("×", Modifier.clickable { state.closeTab(i) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("tab-close-$i"))
            }
        }
    }
    state.closePrompt?.let { prompt ->
        Row(Modifier.padding(bottom = 6.dp).testTag("close-prompt"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Save changes to ${prompt.label}?", style = MaterialTheme.typography.bodySmall)
            Button(contentPadding = CompactButtonPadding, onClick = { state.confirmClose(save = true) }, modifier = Modifier.testTag("close-save")) { Text("Save") }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { state.confirmClose(save = false) }, modifier = Modifier.testTag("close-discard")) { Text("Discard") }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { state.cancelClose() }, modifier = Modifier.testTag("close-cancel")) { Text("Cancel") }
        }
    }
}

@Composable
private fun RecentFilesMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    // The menu is a sibling of the button inside one Box, so it anchors directly under the button.
    Box {
        OutlinedButton(enabled = !state.running && state.recentFiles.isNotEmpty(), onClick = { open = true }) { Text("Recent files") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.recentFiles.forEach { path ->
                DropdownMenuItem(
                    text = { Column { Text(path.fileName.toString()); Text(path.parent?.toString() ?: "", style = MaterialTheme.typography.bodySmall) } },
                    onClick = { open = false; state.openRecent(path) },
                )
            }
        }
    }
}

@Composable
private fun EnvironmentPicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
    // The menu is a sibling of the button inside one Box, so it anchors directly under the button
    // (a DropdownMenu directly in a Column is positioned relative to the whole column).
    Box {
        OutlinedButton(enabled = state.file != null, onClick = { open = true }) {
            Text("Environment: ${state.environment ?: "none"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("none") }, onClick = { state.environment = null; open = false })
            state.environments.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { state.environment = name; open = false })
            }
        }
    }
}

/** Asks for a folder with the Swing chooser (the AWT file dialog cannot pick folders on every platform). */
private fun chooseFolder(parent: Frame?): Path? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = "Open folder"
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun chooseFile(parent: Frame?, title: String, save: Boolean): Path? {
    val dialog = FileDialog(parent, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (!save) dialog.setFilenameFilter { _, name -> name.endsWith(".http") }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val path = Path.of(dialog.directory, name)
    return if (save && !name.endsWith(".http")) path.resolveSibling("$name.http") else path
}
