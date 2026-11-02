package com.dan323.httpclient.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dan323.httpclient.adapters.prettyJson
import com.dan323.httpclient.core.application.RunOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

private val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun RowScope.App(state: AppState) {
    val scope = rememberCoroutineScope()
    var showHeaders by remember { mutableStateOf(false) }

    // Left: file, environment and request list.
    Column(Modifier.width(300.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chooseFile("Open .http file", save = false)?.let(state::open) }) { Text("Open…") }
            OutlinedButton(onClick = { chooseFile("New .http file", save = true)?.let(state::create) }) { Text("New…") }
        }
        Text(state.file?.fileName?.toString()?.plus(if (state.dirty) " •" else "") ?: "No file open", fontWeight = FontWeight.Bold)
        EnvironmentPicker(state)
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(state.requests) { i, r ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (i == state.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { state.selected = i }
                        .padding(8.dp),
                ) {
                    Text(r.name ?: "Request ${i + 1}", fontWeight = FontWeight.SemiBold)
                    Text("${r.method} ${r.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = state.file != null && !state.running, onClick = { scope.launch(Dispatchers.IO) { state.run(all = false) } }) {
                Text(if (state.running) "Running…" else "Send")
            }
            OutlinedButton(enabled = state.file != null && !state.running, onClick = { scope.launch(Dispatchers.IO) { state.run(all = true) } }) {
                Text("Send all")
            }
        }
    }

    // Middle: editor.
    Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.file != null && state.dirty, onClick = { state.save() }) { Text("Save") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = state::edit,
            enabled = state.file != null,
            textStyle = mono,
            placeholder = { Text("Open or create a .http file") },
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        )
    }

    // Right: responses.
    Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Response", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.weight(1f))
            Checkbox(checked = showHeaders, onCheckedChange = { showHeaders = it })
            Text("Headers", modifier = Modifier.padding(top = 12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            state.outcomes.forEach { OutcomeView(it, showHeaders) }
        }
    }
}

@Composable
private fun EnvironmentPicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
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

@Composable
private fun OutcomeView(outcome: RunOutcome, showHeaders: Boolean) {
    val r = outcome.request
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${r.name ?: "${r.method} ${r.url}"}", fontWeight = FontWeight.SemiBold)
        when (outcome) {
            is RunOutcome.Failure -> Text("Error: ${outcome.message}", color = MaterialTheme.colorScheme.error)
            is RunOutcome.Success -> {
                val resp = outcome.response
                val color = if (resp.statusCode < 400) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                Text("HTTP ${resp.statusCode} · ${resp.durationMillis} ms · ${resp.body.size} bytes", color = color)
                SelectionContainer {
                    Column {
                        if (showHeaders) Text(resp.headers.joinToString("\n") { "${it.name}: ${it.value}" }, style = mono)
                        val body = resp.bodyAsString()
                        Text(prettyJson(body) ?: body, style = mono, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

private fun chooseFile(title: String, save: Boolean): Path? {
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (!save) dialog.setFilenameFilter { _, name -> name.endsWith(".http") }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val path = Path.of(dialog.directory, name)
    return if (save && !name.endsWith(".http")) path.resolveSibling("$name.http") else path
}
