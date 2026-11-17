package com.dan323.httpclient.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.viewmodel.findMatches
import com.dan323.httpclient.viewmodel.findLabel

/** What the find bar of the editor holds: whether it is open, the text searched for, the replacement, the case option and a status line. */
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)

    private var status by mutableStateOf<String?>(null)
    private var statusText by mutableStateOf<String?>(null)

    /** Bumped to ask the bar for the keyboard again (Ctrl/Cmd+F while it is already open). */
    var focusTick by mutableStateOf(0)
        private set

    /** Opens the bar, or, when it is open, moves the keyboard to its search box. */
    fun show() { open = true; focusTick++ }

    /** Remembers the result of a Replace all; it is shown only for the [text] that came out of it. */
    fun report(label: String, text: String) { status = label; statusText = text }

    fun clearStatus() { status = null; statusText = null }

    /** The last Replace all's result while the editor still has the text it produced (another edit or tab hides it). */
    fun statusFor(text: String): String? = status.takeIf { statusText == text }

    /** The matches of [query] in [text]; none while the bar is closed. */
    fun matches(text: String): List<IntRange> = if (open) findMatches(text, query, matchCase) else emptyList()
}

/**
 * Find and replace for the editor: the search box with the count, Prev / Next (Enter / Shift+Enter in the box), the
 * case option, the replacement with Replace and Replace all, and Close (Esc). The editor does the work through the callbacks.
 */
@Composable
internal fun FindBar(
    find: FindState,
    matchCount: Int,
    status: String?,
    currentIndex: Int?,
    canEdit: Boolean,
    onStep: (forward: Boolean) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(find.focusTick) { focus.requestFocus() }
    fun keys(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when (e.key) {
            Key.Enter, Key.NumPadEnter -> { onStep(!e.isShiftPressed); true }
            Key.Escape -> { onClose(); true }
            else -> false
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp).testTag("find-bar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = find.query, onValueChange = { find.query = it; find.clearStatus() },
                singleLine = true, textStyle = MaterialTheme.typography.bodySmall, placeholder = { Text("Find") },
                modifier = Modifier.weight(1f).height(48.dp).focusRequester(focus).testTag("find-query").onPreviewKeyEvent(::keys),
            )
            Checkbox(checked = find.matchCase, onCheckedChange = { find.matchCase = it; find.clearStatus() }, modifier = Modifier.testTag("find-case"))
            Text("Match case", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                status ?: findLabel(find.query, currentIndex, matchCount), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).testTag("find-count"),
            )
            OutlinedButton(enabled = matchCount > 0, contentPadding = CompactButtonPadding, onClick = { onStep(false) }, modifier = Modifier.testTag("find-prev")) { Text("Prev", maxLines = 1) }
            OutlinedButton(enabled = matchCount > 0, contentPadding = CompactButtonPadding, onClick = { onStep(true) }, modifier = Modifier.testTag("find-next")) { Text("Next", maxLines = 1) }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = onClose, modifier = Modifier.testTag("find-close")) { Text("Close", maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = find.replacement, onValueChange = { find.replacement = it; find.clearStatus() },
                singleLine = true, textStyle = MaterialTheme.typography.bodySmall, placeholder = { Text("Replace with") },
                modifier = Modifier.weight(1f).height(48.dp).testTag("find-replace").onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false },
            )
            OutlinedButton(enabled = canEdit && matchCount > 0, contentPadding = CompactButtonPadding, onClick = onReplace, modifier = Modifier.testTag("find-replace-one")) { Text("Replace", maxLines = 1) }
            OutlinedButton(enabled = canEdit && matchCount > 0, contentPadding = CompactButtonPadding, onClick = onReplaceAll, modifier = Modifier.testTag("find-replace-all")) { Text("Replace all", maxLines = 1) }
        }
    }
}
