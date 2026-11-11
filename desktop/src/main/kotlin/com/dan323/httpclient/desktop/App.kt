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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.viewmodel.RunControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** Tight horizontal padding so the three buttons under the request list fit the 300 dp column. */
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

/** How long the transient notice (e.g. "Chain reset") stays visible. */
private const val NOTICE_MILLIS = 4_000L

internal val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun RowScope.App(state: AppState, parent: Frame? = null) {
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    // Left: file, environment and request list.
    Column(Modifier.width(300.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.running, onClick = { state.pick { chooseFile(parent, "Open .http file", save = false) }?.let(state::open) }) { Text("Open…") }
            OutlinedButton(enabled = !state.running, onClick = { state.pick { chooseFile(parent, "New .http file", save = true) }?.let(state::create) }) { Text("New…") }
        }
        Text(state.file?.fileName?.toString()?.plus(if (state.dirty) " •" else "") ?: "No file open", fontWeight = FontWeight.Bold)
        RecentFilesMenu(state)
        EnvironmentPicker(state)
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

    // Middle: editor.
    Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.file != null && state.dirty && !state.running, onClick = { state.save() }) { Text("Save") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = state::edit,
            enabled = state.file != null,
            readOnly = state.running,
            textStyle = mono,
            visualTransformation = remember(dark) { HttpVisualTransformation(if (dark) HighlightPalette.Dark else HighlightPalette.Light) },
            placeholder = { Text("Open or create a .http file") },
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        )
    }

    // Right: responses.
    Column(Modifier.weight(1.2f).fillMaxHeight().padding(12.dp)) {
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
                if (state.running) "Sending…" else "Select a request and press Send (Ctrl/Cmd+Enter).",
                color = MaterialTheme.colorScheme.outline,
            )
        }
        ResponsePane(state.outcomes, Modifier.fillMaxSize())
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

private fun chooseFile(parent: Frame?, title: String, save: Boolean): Path? {
    val dialog = FileDialog(parent, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (!save) dialog.setFilenameFilter { _, name -> name.endsWith(".http") }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val path = Path.of(dialog.directory, name)
    return if (save && !name.endsWith(".http")) path.resolveSibling("$name.http") else path
}
