package com.dan323.httpclient.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.viewmodel.FolderContents
import com.dan323.httpclient.viewmodel.FolderEntry
import com.dan323.httpclient.viewmodel.FolderListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** One visible line of the tree: an [entry] at [depth], with whether a folder is [expanded]. */
internal class FolderRow(val entry: FolderEntry?, val depth: Int, val expanded: Boolean, val note: String? = null)

/**
 * The tree of the folder view: which folders are open and what each one held when it was listed. A folder is listed
 * (off the UI thread, by [load]) when it is first opened; [reload] forgets everything and lists the root again.
 */
internal class FolderTreeState(val root: Path, private val list: (Path) -> FolderContents = FolderListing::list) {
    private val expanded = mutableStateMapOf<Path, Boolean>()
    private val contents = mutableStateMapOf<Path, FolderContents>()

    fun isExpanded(dir: Path) = expanded[dir] == true

    /** Lists [dir] unless it already was; blocking. */
    suspend fun load(dir: Path) {
        if (dir in contents) return
        contents[dir] = withContext(Dispatchers.IO) { list(dir) }
    }

    suspend fun toggle(dir: Path) {
        if (isExpanded(dir)) { expanded.remove(dir); return }
        load(dir)
        expanded[dir] = true
    }

    suspend fun reload() {
        contents.clear()
        load(root)   // the opened folders are listed again as they are drawn
        for (dir in expanded.keys.toList()) load(dir)
    }

    /** The visible lines, depth first: the root's entries, and below each opened folder its own. */
    fun rows(): List<FolderRow> {
        val out = mutableListOf<FolderRow>()
        fun add(dir: Path, depth: Int) {
            val c = contents[dir] ?: return
            c.error?.let { out += FolderRow(null, depth, false, "Could not read this folder: $it") }
            if (c.entries.isEmpty() && c.error == null) out += FolderRow(null, depth, false, "(no .http files)")
            for (e in c.entries) {
                val open = e.directory && isExpanded(e.path)
                out += FolderRow(e, depth, open)
                if (open) add(e.path, depth + 1)
            }
            if (c.hidden > 0) out += FolderRow(null, depth, false, "… and ${c.hidden} more")
        }
        add(root, 0)
        return out
    }
}

/** The folder view: the `.http` files below a folder as a tree; a click on a file opens it in a tab. */
@Composable
internal fun FolderPanel(state: AppState, pickFolder: () -> Path?, modifier: Modifier = Modifier) {
    val root = state.folderRoot
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Folder", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("×", Modifier.clickable { state.showFolder(false) }.padding(horizontal = 6.dp).testTag("folder-close"))
        }
        if (root == null || !Files.isDirectory(root)) {
            Text(
                if (root == null) "Open a file or pick a folder." else "The folder ${root.fileName ?: root} does not exist.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        } else {
            val tree = remember(root) { FolderTreeState(root) }
            LaunchedEffect(tree) { tree.load(root) }
            Text(root.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
            val current = state.file?.let { canonical(it) }
            LazyColumn(Modifier.weight(1f).testTag("folder-tree")) {
                itemsIndexed(tree.rows()) { i, row ->
                    val e = row.entry
                    if (e == null) {
                        Text(row.note ?: "", Modifier.padding(start = (8 + row.depth * 12).dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        val shown = !e.directory && current == canonical(e.path)
                        Text(
                            (if (e.directory) (if (row.expanded) "▾ " else "▸ ") else "") + e.name,
                            Modifier.fillMaxWidth().testTag("folder-row-${e.name}")
                                .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { if (e.directory) scope.launch { tree.toggle(e.path) } else state.open(e.path) }
                                .padding(start = (8 + row.depth * 12).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
                            maxLines = 1,
                            fontWeight = if (e.directory) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { scope.launch { tree.reload() } }, modifier = Modifier.testTag("folder-reload")) { Text("Reload", maxLines = 1) }
        }
        OutlinedButton(contentPadding = CompactButtonPadding, onClick = { pickFolder()?.let(state::chooseFolder) }, modifier = Modifier.testTag("folder-pick")) { Text("Open folder…", maxLines = 1) }
    }
}
