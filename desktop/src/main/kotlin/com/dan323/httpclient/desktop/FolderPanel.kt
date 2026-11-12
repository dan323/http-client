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
import androidx.compose.runtime.produceState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dan323.httpclient.viewmodel.FolderTree
import com.dan323.httpclient.viewmodel.FolderListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The state holder of the folder view: the current [FolderTree] (replaced after each change, so composition sees it)
 * and the listing, which runs off the UI thread. Changes are serialised, so a double click or a reload during a toggle
 * cannot interleave.
 */
internal class FolderTreeState(root: Path, private val list: (Path) -> FolderContents = FolderListing::list) {
    var tree by mutableStateOf(FolderTree(root))
        private set
    private val mutex = Mutex()

    val root: Path get() = tree.root

    /** Lists the root unless that was done. */
    suspend fun load() = mutex.withLock { if (!tree.isListed(tree.root)) listInto(tree.root) }

    /** Opens or closes [dir]; an opened folder is listed first. */
    suspend fun toggle(dir: Path) = mutex.withLock {
        if (!tree.isExpanded(dir) && !tree.isListed(dir)) listInto(dir)
        tree = tree.toggled(dir)
    }

    /** Lists the root and the open folders again (in parallel) and swaps the new listings in at once, so the tree never goes blank. */
    suspend fun reload() = mutex.withLock {
        val folders = tree.foldersToList()
        val fresh = coroutineScope { folders.map { d -> async(Dispatchers.IO) { d to list(d) } }.awaitAll() }.toMap()
        tree = tree.reloaded(fresh)
    }

    private suspend fun listInto(dir: Path) {
        val listed = withContext(Dispatchers.IO) { list(dir) }
        tree = tree.withListing(dir, listed)
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
        if (root == null) {
            Text("Open a file or pick a folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            val tree = remember(root) { FolderTreeState(root) }
            LaunchedEffect(tree) { tree.load() }
            Text(root.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
            // The file on screen as a key like the entries have; resolved off the UI thread (it touches the disk).
            val current by produceState<String?>(null, state.file) { value = state.file?.let { f -> withContext(Dispatchers.IO) { FolderListing.realKey(f) } } }
            val rows = tree.tree.rows()
            LazyColumn(Modifier.weight(1f).testTag("folder-tree")) {
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    val e = row.entry
                    if (e == null) {
                        Text(row.note ?: "", Modifier.padding(start = (8 + row.depth * 12).dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        val shown = !e.directory && current == e.key
                        Text(
                            (if (e.directory) (if (row.expanded) "▾ " else "▸ ") else "") + e.name,
                            Modifier.fillMaxWidth().testTag("folder-row-${e.name}")
                                .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable(enabled = e.directory || !state.running) { if (e.directory) scope.launch { tree.toggle(e.path) } else state.open(e.path) }
                                .padding(start = (8 + row.depth * 12).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
                            maxLines = 1,
                            fontWeight = if (e.directory) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (!e.directory && state.running) MaterialTheme.colorScheme.outline else Color.Unspecified,
                        )
                    }
                }
            }
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { scope.launch { tree.reload() } }, modifier = Modifier.testTag("folder-reload")) { Text("Reload", maxLines = 1) }
        }
        OutlinedButton(contentPadding = CompactButtonPadding, onClick = { pickFolder()?.let(state::chooseFolder) }, modifier = Modifier.testTag("folder-pick")) { Text("Open folder…", maxLines = 1) }
        if (state.chosenFolder != null) {
            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { state.followFile() }, modifier = Modifier.testTag("folder-follow")) { Text("Folder of the open file", maxLines = 1) }
        }
    }
}
