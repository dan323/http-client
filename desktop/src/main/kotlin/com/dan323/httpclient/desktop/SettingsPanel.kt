package com.dan323.httpclient.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Replaces the response pane: what a `.http` file may read besides its own folder. Kept in the app's own settings
 * (never in a project file), added to `-Dhttpclient.allowEnv` / `HTTPC_ALLOW_ENV` and `-Dhttpclient.allowFileRoots` /
 * `HTTPC_ALLOW_FILE_ROOTS`.
 */
@Composable
fun SettingsPanel(state: AppState, draft: SettingsDraft, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(contentPadding = CompactButtonPadding, onClick = { state.saveSettings() }, modifier = Modifier.testTag("settings-save")) { Text("Save") }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { state.showSettings(false) }, modifier = Modifier.testTag("settings-close")) {
                Text(if (draft.dirty) "Discard and close" else "Close")
            }
        }
        Text(
            "A .http file reads nothing outside its folder and no OS variable unless you allow it here. Allow only what you trust every file you open to read.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        Text("Allowed environment variables", fontWeight = FontWeight.SemiBold)
        Text("One name per line, matched exactly. {{\$env.NAME}} reads only these.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.envText, { draft.envText = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("settings-env"), minLines = 3)
        Text("Allowed folders", fontWeight = FontWeight.SemiBold)
        Text("One absolute folder per line (~ for your home folder). < path bodies and >> path saves may use them, besides the .http file's folder.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.rootsText, { draft.rootsText = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("settings-roots"), minLines = 3)
        draft.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-error")) }
        Text(
            "Also read at start: -Dhttpclient.allowEnv / HTTPC_ALLOW_ENV and -Dhttpclient.allowFileRoots / HTTPC_ALLOW_FILE_ROOTS.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}
