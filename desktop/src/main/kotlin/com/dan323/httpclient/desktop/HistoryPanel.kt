package com.dan323.httpclient.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.viewmodel.BodyDiff
import com.dan323.httpclient.viewmodel.DiffKind
import com.dan323.httpclient.viewmodel.HeaderChange
import com.dan323.httpclient.viewmodel.ResponseDiff
import com.dan323.httpclient.viewmodel.asRequest
import com.dan323.httpclient.viewmodel.entryLabel
import com.dan323.httpclient.viewmodel.runLabel
import com.dan323.httpclient.viewmodel.sizeLabel
import com.dan323.httpclient.viewmodel.storedLabel
import com.dan323.httpclient.viewmodel.truncationNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val AddedColor = Color(0xFF2E7D32)
private val RemovedColor = Color(0xFFC62828)

/** Replaces the response pane: recorded runs of the open file, one stored response, and a comparison of two of them. */
@Composable
fun HistoryPanel(state: AppState, modifier: Modifier = Modifier) {
    val history = state.historyView ?: return
    val scope = rememberCoroutineScope()
    val diff = history.diff
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        history.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (diff != null) {
            OutlinedButton(onClick = { history.closeCompare() }) { Text("Back to history") }
            DiffView(diff, Modifier.fillMaxSize())
            return@Column
        }
        Text("Runs", fontWeight = FontWeight.Bold)
        if (history.runs.isEmpty()) {
            Text(
                if (state.file == null) "Open a file to see its history." else "No recorded runs. Turn on Record history to keep them.",
                color = MaterialTheme.colorScheme.outline,
            )
        }
        LazyColumn(Modifier.heightIn(max = 140.dp)) {
            itemsIndexed(history.runs) { i, run ->
                Text(
                    runLabel(run),
                    Modifier.fillMaxWidth().testTag("history-run-$i")
                        .background(if (run.id == history.selectedRun?.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { history.selectRun(run.id) }.padding(6.dp),
                )
            }
        }
        val run = history.selectedRun
        if (run != null) {
            HorizontalDivider()
            Text("Requests", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 140.dp)) {
                itemsIndexed(run.report.entries) { i, entry ->
                    val hasResponse = entry.status != null
                    Text(
                        entryLabel(entry),
                        Modifier.fillMaxWidth().testTag("history-entry-$i")
                            .background(if (i == history.openedIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .let { if (hasResponse) it.clickable { scope.launch(Dispatchers.IO) { history.open(i) } } else it }
                            .padding(6.dp),
                        color = if (hasResponse) Color.Unspecified else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        val opened = history.opened
        if (opened != null) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Stored response: ${storedLabel(opened)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { history.markOpenedAsA() }) { Text("Mark as A") }
                OutlinedButton(onClick = { history.markOpenedAsB() }) { Text("Mark as B") }
            }
            truncationNotice(opened)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            val shown = remember(opened) { listOf<RunOutcome>(RunOutcome.Success(opened.asRequest(), opened.response)) }
            ResponsePane(shown, Modifier.weight(1f).fillMaxWidth())
        }
        if (history.markA != null || history.markB != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "A: ${history.markA?.let { storedLabel(it) } ?: "not marked"}   B: ${history.markB?.let { storedLabel(it) } ?: "not marked"}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                OutlinedButton(enabled = history.canCompare, onClick = { history.compare() }, modifier = Modifier.testTag("history-compare")) { Text("Compare") }
            }
        }
    }
}

/** The comparison: status and duration, changed headers, and a body diff whose lines carry `+` / `-` as well as colour. */
@Composable
private fun DiffView(diff: ResponseDiff, modifier: Modifier = Modifier) {
    SelectionContainer {
        LazyColumn(modifier) {
            item { Text(diff.statusLabel, fontWeight = FontWeight.Bold, color = if (diff.statusChanged) RemovedColor else Color.Unspecified) }
            item { Text(diff.durationsLabel, style = MaterialTheme.typography.bodySmall) }
            if (diff.identical) item { Text("The responses are the same (ignoring duration).", Modifier.padding(top = 8.dp)) }
            if (diff.headers.isNotEmpty()) {
                item { Text("Headers", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(diff.headers.map(::headerLine)) { (text, color) -> Text(text, style = mono, color = color) }
            }
            item { Text("Body", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            when (val body = diff.body) {
                BodyDiff.Same -> item { Text("The bodies are the same.") }
                is BodyDiff.Skipped -> item {
                    Text("Bodies not compared: ${body.reason} (A: ${sizeLabel(body.sizeA)}, B: ${sizeLabel(body.sizeB)}).", color = MaterialTheme.colorScheme.outline)
                }
                is BodyDiff.Lines -> items(body.lines) { line ->
                    Text(
                        line.label, style = mono,
                        color = when (line.kind) { DiffKind.ADDED -> AddedColor; DiffKind.REMOVED -> RemovedColor; DiffKind.SAME -> Color.Unspecified },
                    )
                }
            }
        }
    }
}

private fun headerLine(c: HeaderChange): Pair<String, Color> = when (c) {
    is HeaderChange.Added -> "+ ${c.name}: ${c.value}" to AddedColor
    is HeaderChange.Removed -> "- ${c.name}: ${c.value}" to RemovedColor
    is HeaderChange.Changed -> "~ ${c.name}: ${c.before}  →  ${c.after}" to Color.Unspecified
}
