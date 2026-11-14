package com.dan323.httpclient.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.viewmodel.EditorEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Replaces the response pane: the environments of the open file's folder as tables of variables. A variable marked
 * Private is saved in `http-client.private.env.json` (keep it out of version control) and masked until Show private values is on.
 */
@Composable
fun EnvironmentPanel(state: AppState, editor: EnvironmentEditorState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val draft = editor.draft
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Environments", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Checkbox(checked = editor.reveal, onCheckedChange = { editor.reveal = it }, modifier = Modifier.testTag("env-reveal"))
            Text("Show private values", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = editor.loadError == null && editor.dirty,
                contentPadding = CompactButtonPadding,
                onClick = { scope.launch(Dispatchers.IO) { state.saveEnvironments() } },
                modifier = Modifier.testTag("env-save"),
            ) { Text("Save") }
            OutlinedButton(
                contentPadding = CompactButtonPadding,
                onClick = { scope.launch(Dispatchers.IO) { editor.load() } },
                modifier = Modifier.testTag("env-reload"),
            ) { Text("Reload") }
            OutlinedButton(
                contentPadding = CompactButtonPadding,
                onClick = { state.showEnvironmentEditor(false) },
                modifier = Modifier.testTag("env-close"),
            ) { Text(if (editor.dirty) "Discard and close" else "Close") }
        }
        Text(
            "Private variables are saved in http-client.private.env.json; keep that file out of version control.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        editor.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("env-load-error")) }
        editor.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("env-status")) }
        editor.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (editor.loadError != null) return@Column
        if (draft.environments.isEmpty()) Text("No environments yet. Add one to start.", color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(draft.environments) { i, env -> EnvironmentBlock(editor, i, env) }
        }
        OutlinedButton(onClick = { editor.change { it.addEnvironment() } }, modifier = Modifier.testTag("env-add")) { Text("Add environment") }
    }
}

@Composable
private fun EnvironmentBlock(editor: EnvironmentEditorState, i: Int, env: EditorEnvironment) {
    Column(Modifier.fillMaxWidth().testTag("env-$i"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = env.name, onValueChange = { n -> editor.change { it.renameEnvironment(i, n) } },
                label = { Text("Environment") }, singleLine = true, modifier = Modifier.weight(1f).testTag("env-name-$i"),
            )
            if (!env.locked) OutlinedButton(contentPadding = CompactButtonPadding, onClick = { editor.change { it.addVariable(i) } }, modifier = Modifier.testTag("env-add-var-$i")) { Text("Add variable") }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { editor.change { it.removeEnvironment(i) } }, modifier = Modifier.testTag("env-remove-$i")) { Text("Remove") }
        }
        if (env.locked) {
            Text(
                "A file holds this environment as something other than an object, so its variables cannot be edited here. It can still be renamed or removed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        env.variables.forEachIndexed { j, v ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = v.name, onValueChange = { n -> editor.change { it.updateVariable(i, j) { row -> row.copy(name = n) } } },
                    placeholder = { Text("name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("env-var-name-$i-$j"),
                )
                OutlinedTextField(
                    value = v.value, onValueChange = { t -> editor.change { it.updateVariable(i, j) { row -> row.copy(value = t) } } },
                    placeholder = { Text("value") }, singleLine = true,
                    visualTransformation = if (v.private && !editor.reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.weight(1.5f).testTag("env-var-value-$i-$j"),
                )
                Checkbox(checked = v.private, onCheckedChange = { p -> editor.change { it.updateVariable(i, j) { row -> row.copy(private = p) } } }, modifier = Modifier.testTag("env-var-private-$i-$j"))
                Text("Private", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(contentPadding = CompactButtonPadding, onClick = { editor.change { it.removeVariable(i, j) } }, modifier = Modifier.testTag("env-var-remove-$i-$j")) { Text("×") }
            }
        }
    }
}
