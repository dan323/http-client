package com.dan323.httpclient.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.dan323.httpclient.viewmodel.findMatches
import com.dan323.httpclient.viewmodel.matchLabel
import com.dan323.httpclient.viewmodel.stepMatch
import com.dan323.httpclient.viewmodel.suggestedFileName
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.viewmodel.ImageFormat
import com.dan323.httpclient.viewmodel.JsonKind
import com.dan323.httpclient.viewmodel.imageFormatOf
import com.dan323.httpclient.viewmodel.imageInfoLabel
import com.dan323.httpclient.viewmodel.bodyRefusal
import com.dan323.httpclient.viewmodel.previewRefusal
import com.dan323.httpclient.viewmodel.JsonRow
import com.dan323.httpclient.viewmodel.StatusClass
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.display
import com.dan323.httpclient.viewmodel.flattenJson
import com.dan323.httpclient.viewmodel.parseJsonContainer
import kotlinx.serialization.json.JsonElement
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal enum class Tab { BODY, HEADERS, PREVIEW }

private val SearchClock = java.util.concurrent.atomic.AtomicLong()

/** Height of the sticky search row; a match is scrolled this far below the top edge so the row does not cover it. */
private val SearchRowHeight = 104.dp

/** A previewable image body: its type and bytes. Only set for a response with an image type and a non-empty body. */
internal class ImageSource(val format: ImageFormat, val bytes: ByteArray)

/**
 * What the user has toggled for one response: tab, raw/pretty, and which JSON nodes are collapsed. [image] is set
 * when the response is a previewable image; such a response opens on the Preview tab.
 */
internal class OutcomeUi(val json: JsonElement?, val image: ImageSource? = null) {
    var tab by mutableStateOf(if (image != null) Tab.PREVIEW else Tab.BODY)

    /** The decoded image, kept so switching tabs or scrolling does not decode it again; null until decoded. */
    var decoded by mutableStateOf<DecodedImage?>(null)
    var raw by mutableStateOf(false)
    val collapsed = mutableStateSetOf<String>()

    /** The text searched for in the body (empty: no search) and the match the view is on. */
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var current by mutableStateOf(0)

    /** When this response's search last changed (a global sequence number); the pane scrolls to the most recent one. */
    var touched by mutableStateOf(0L)

    /** The layout of the searched text, to find the line of a match; [layoutVersion] changes when it does. */
    var layout: TextLayoutResult? = null
    var layoutVersion by mutableStateOf(0)

    fun touch() { touched = SearchClock.incrementAndGet() }

    /** The result of the last **Save body**, shown under the header. */
    var saveMessage by mutableStateOf<String?>(null)
}

internal class OutcomeEntry(val display: OutcomeDisplay, val ui: OutcomeUi?)

/** Identity key: two equal outcomes (e.g. the same failure twice) are separate sections with separate UI state. */
private class IdKey(val outcome: RunOutcome) {
    override fun equals(other: Any?) = other is IdKey && other.outcome === outcome
    override fun hashCode() = System.identityHashCode(outcome)
}

/**
 * Display data for the outcomes on screen. Decoding and JSON parsing happen on [Dispatchers.Default], not in
 * composition, entries for outcomes that left the list are dropped so a long session does not retain them, and an
 * outcome already processed is never processed again. A body that cannot be displayed becomes an error section
 * (with the raw body) instead of an exception in composition.
 */
internal class OutcomeModels(
    private val render: (RunOutcome) -> OutcomeDisplay = { it.display() },
    private val parseJson: (String) -> JsonElement? = { parseJsonContainer(it) },
) {
    private val entries = mutableStateMapOf<IdKey, OutcomeEntry>()
    val size get() = entries.size
    operator fun get(outcome: RunOutcome): OutcomeEntry? = entries[IdKey(outcome)]

    suspend fun update(outcomes: List<RunOutcome>) {
        val live = outcomes.map { IdKey(it) }.toSet()
        entries.keys.retainAll(live)
        for (o in outcomes) {
            val key = IdKey(o)
            if (key in entries) continue
            entries[key] = withContext(Dispatchers.Default) { build(o) }
        }
    }

    private fun build(o: RunOutcome): OutcomeEntry = try {
        val d = render(o)
        val ui = (d as? OutcomeDisplay.Response)?.let { r ->
            val json = try { parseJson(r.rawBody) } catch (e: Exception) { null }   // unparsable: show the raw body
            val body = (o as? RunOutcome.Success)?.response?.body
            val format = imageFormatOf(r.contentType)
            OutcomeUi(json, if (format != null && body != null && body.isNotEmpty()) ImageSource(format, body) else null)
        }
        OutcomeEntry(d, ui)
    } catch (e: Exception) {
        val title = o.request.name ?: "${o.request.method} ${o.request.url}"
        val body = (o as? RunOutcome.Success)?.let { runCatching { it.response.bodyAsString() }.getOrNull() }
        val detail = "Could not display this response (${e.message ?: e::class.simpleName})" + (body?.let { ":\n$it" } ?: "")
        OutcomeEntry(OutcomeDisplay.Error(title, "", detail), null)
    }
}

private class JsonColors(val key: Color, val string: Color, val number: Color, val keyword: Color, val punctuation: Color, val muted: Color) {
    companion object {
        val Light = JsonColors(Color(0xFF871094), Color(0xFF067D17), Color(0xFF1750EB), Color(0xFF0033B3), Color(0xFF6A6A6A), Color(0xFF8C8C8C))
        val Dark = JsonColors(Color(0xFFC77DBB), Color(0xFF6AAB73), Color(0xFF2AACB8), Color(0xFFCF8E6D), Color(0xFF9A9DA3), Color(0xFF7A7E85))
    }
}

/** What one response section shows: [highlighted] replaces the JSON tree and the text while a search is active. */
internal class Section(
    val display: OutcomeDisplay,
    val ui: OutcomeUi?,
    val rows: List<JsonRow>?,
    val outcome: RunOutcome,
    val highlighted: AnnotatedString?,
    /** Start of the current match in the shown text; null without matches. */
    val matchStart: Int?,
    val matchCount: Int,
) {
    /** Header, search row (body tab of a response), content, divider. */
    val itemCount: Int get() = 1 + (if (hasSearchRow) 1 else 0) + contentCount + 1
    val hasSearchRow: Boolean get() = display is OutcomeDisplay.Response && ui != null && ui.tab == Tab.BODY && ui.searchOpen
    private val contentCount: Int get() = when {
        display !is OutcomeDisplay.Response || ui == null -> 0
        ui.tab == Tab.HEADERS -> display.headers.size
        ui.tab == Tab.PREVIEW -> 1
        highlighted != null -> 1
        rows != null -> rows.size
        else -> 1
    }
}

/** Where to scroll to show a match: the list item that holds the text, and the match's offset in it. */
internal class ScrollTarget(val ui: OutcomeUi, val item: Int, val matchStart: Int)

/** The match of the response whose search changed last (not just the first response that has a match), or null. */
internal fun scrollTargetOf(sections: List<Section>): ScrollTarget? {
    var before = 0
    var best: ScrollTarget? = null
    var bestTouched = Long.MIN_VALUE
    for (sec in sections) {
        val ui = sec.ui
        if (ui != null && sec.matchStart != null && sec.highlighted != null && ui.touched >= bestTouched) {
            bestTouched = ui.touched
            best = ScrollTarget(ui, before + 2, sec.matchStart)   // header, search row, then the text item
        }
        before += sec.itemCount
    }
    return best
}

private val MatchColor = Color(0x66FFD54F)
private val CurrentMatchColor = Color(0xCCFF9800)

/** [text] with the [matches] (ranges over the whole text) highlighted and the [current] one stronger. */
internal fun highlightText(text: String, matches: List<IntRange>, current: Int): AnnotatedString = buildAnnotatedString {
    append(text)
    matches.forEachIndexed { i, m -> addStyle(SpanStyle(background = if (i == current) CurrentMatchColor else MatchColor), m.first, m.last + 1) }
}

/** The section of [o] for display. The search work is remembered, so it is not redone for unrelated recompositions. */
@Composable
private fun rememberSection(o: RunOutcome, entry: OutcomeEntry): Section {
    val ui = entry.ui
    val display = entry.display
    val response = display as? OutcomeDisplay.Response
    val searching = response != null && ui != null && ui.tab == Tab.BODY && ui.searchOpen && ui.query.isNotEmpty()
    val text = if (response != null && ui != null) (if (ui.raw) response.rawBody else response.prettyBody) else ""
    val query = if (searching) ui!!.query else ""
    val matches = remember(text, query) { findMatches(text, query) }
    val current = if (matches.isEmpty()) 0 else ui!!.current.coerceIn(0, matches.size - 1)
    val highlighted = if (searching) remember(text, matches, current) { highlightText(text, matches, current) } else null
    val rows = if (ui?.json != null && ui.tab == Tab.BODY && !ui.raw && !searching) flattenJson(ui.json, ui.collapsed) else null
    return Section(display, ui, rows, o, highlighted, matches.getOrNull(current)?.first, matches.size)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResponsePane(outcomes: List<RunOutcome>, modifier: Modifier = Modifier, bodyCutNote: String? = null) {
    val colors = if (isSystemInDarkTheme()) JsonColors.Dark else JsonColors.Light
    val models = remember { OutcomeModels() }
    LaunchedEffect(outcomes) { models.update(outcomes) }
    val listState = rememberLazyListState()
    // Read the collapsed sets and the search here, in composition, so toggling a node or typing recomposes the list.
    val sections = outcomes.mapNotNull { o -> models[o]?.let { rememberSection(o, it) } }

    // Scroll to the current match of the response searched last, a little below the top so the sticky search row does not cover it.
    val target = scrollTargetOf(sections)
    val stickyPx = with(LocalDensity.current) { SearchRowHeight.roundToPx() }
    LaunchedEffect(target?.ui, target?.ui?.touched, target?.ui?.layoutVersion, target?.item) {
        val t = target ?: return@LaunchedEffect
        val layout = t.ui.layout
        val top = layout?.let { it.getLineTop(it.getLineForOffset(t.matchStart.coerceAtMost(it.layoutInput.text.length))).toInt() } ?: 0
        listState.animateScrollToItem(t.item, top - stickyPx)
    }

    LazyColumn(modifier, state = listState) {
        for (sec in sections) {
            val outcome = sec.display
            val ui = sec.ui
            item { OutcomeHeader(outcome, ui, sec.outcome, bodyCutNote) }
            if (sec.hasSearchRow && ui != null) stickyHeader { SearchRow(ui, sec.matchCount) }
            if (outcome is OutcomeDisplay.Response && ui != null) {
                when {
                    ui.tab == Tab.HEADERS -> items(outcome.headers) { (name, value) ->
                        SelectionContainer {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)) { append(name) }
                                    append(": ")
                                    append(value)
                                },
                                style = mono, modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                    ui.tab == Tab.PREVIEW -> item { ui.image?.let { ImagePreview(ui, it, bodyCutNote) } }
                    sec.highlighted != null -> item {
                        SelectionContainer {
                            Text(sec.highlighted, style = mono, onTextLayout = { l ->
                                val changed = ui.layout?.let { it.size != l.size || it.layoutInput.text != l.layoutInput.text } ?: true
                                ui.layout = l
                                if (changed) ui.layoutVersion++
                            })
                        }
                    }
                    sec.rows != null -> items(sec.rows) { row -> JsonRowView(row, colors) { ui.toggle(row.path) } }
                    else -> item {
                        val text = if (ui.raw) outcome.rawBody else outcome.prettyBody
                        SelectionContainer {
                            Text(text.ifEmpty { "(empty body)" }, style = mono)
                        }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        }
    }
}

/** The search box of one response: the count, previous and next (Enter / Shift+Enter in the box do the same). */
@Composable
private fun SearchRow(ui: OutcomeUi, count: Int) {
    val current = if (count == 0) 0 else ui.current.coerceIn(0, count - 1)
    fun step(forward: Boolean) { ui.current = stepMatch(current, count, forward); ui.touch() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }   // typing can start at once
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The field has a row of its own, so a narrow pane cannot squeeze it to nothing; the count and the steps sit below.
        OutlinedTextField(
            value = ui.query, onValueChange = { ui.query = it; ui.current = 0; ui.touch() },
            singleLine = true, textStyle = MaterialTheme.typography.bodySmall, placeholder = { Text("Search in body") },
            modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(focus).testTag("body-search").onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { step(!e.isShiftPressed); true } else false
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(matchLabel(ui.query, current, count), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("body-search-count"))
            OutlinedButton(enabled = count > 1, contentPadding = CompactButtonPadding, onClick = { step(false) }, modifier = Modifier.testTag("body-search-prev")) { Text("Prev", maxLines = 1) }
            OutlinedButton(enabled = count > 1, contentPadding = CompactButtonPadding, onClick = { step(true) }, modifier = Modifier.testTag("body-search-next")) { Text("Next", maxLines = 1) }
        }
    }
}

private fun OutcomeUi.toggle(path: String) { if (!collapsed.remove(path)) collapsed.add(path) }

@Composable
private fun OutcomeHeader(outcome: OutcomeDisplay, ui: OutcomeUi?, source: RunOutcome, bodyCutNote: String?) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(outcome.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        when (outcome) {
            is OutcomeDisplay.Error -> {
                Text("Error: ${outcome.message}", color = MaterialTheme.colorScheme.error)
                outcome.assertionsNotRunLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            }
            is OutcomeDisplay.Reused -> Text("Reused earlier response (not sent again)", style = MaterialTheme.typography.bodySmall)
            is OutcomeDisplay.Response -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(outcome)
                    Text(outcome.durationLabel, style = MaterialTheme.typography.bodySmall)
                    Text(outcome.sizeLabel, style = MaterialTheme.typography.bodySmall)
                    outcome.contentType?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
                outcome.notices.forEach {
                    Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                }
                AssertionResults(outcome)
                if (ui != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(ui.tab == Tab.BODY, { ui.tab = Tab.BODY }, label = { Text("Body") })
                        FilterChip(ui.tab == Tab.HEADERS, { ui.tab = Tab.HEADERS }, label = { Text("Headers (${outcome.headers.size})") })
                        if (ui.image != null) {
                            FilterChip(ui.tab == Tab.PREVIEW, { ui.tab = Tab.PREVIEW }, label = { Text("Preview") }, modifier = Modifier.testTag("tab-preview"))
                        }
                        Box(Modifier.weight(1f))
                        if (ui.tab == Tab.BODY && ui.json != null) {
                            FilterChip(!ui.raw, { ui.raw = false }, label = { Text("Pretty") })
                            FilterChip(ui.raw, { ui.raw = true }, label = { Text("Raw") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.tab == Tab.BODY) {
                            FilterChip(ui.searchOpen, { ui.searchOpen = !ui.searchOpen; if (!ui.searchOpen) { ui.query = ""; ui.current = 0 } else ui.touch() },
                                label = { Text("Search") }, modifier = Modifier.testTag("body-search-toggle"))
                        }
                        if (ui.tab != Tab.PREVIEW) {
                            OutlinedButton(contentPadding = CompactButtonPadding, onClick = { copy(copyText(outcome, ui)) }) { Text("Copy", maxLines = 1) }
                        }
                        (source as? RunOutcome.Success)?.let { ok ->
                            OutlinedButton(
                                contentPadding = CompactButtonPadding, onClick = {
                                    val target = chooseSavePath(ok)
                                    if (target != null) {
                                        ui.saveMessage = "Saving…"
                                        // The write can be slow (a big body, a network drive): off the UI thread.
                                        scope.launch(Dispatchers.IO) {
                                            val message = writeBody(target, ok.response.body) + (bodyCutNote?.let { " Not the whole response: $it" } ?: "")
                                            ui.saveMessage = message   // Compose state may be set from any thread
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("save-body"),
                            ) { Text("Save body", maxLines = 1) }
                        }
                    }
                    ui.saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
            }
        }
    }
}

/** The summary line and one PASS/FAIL line per assertion; nothing when the request has no assertions. */
@Composable
private fun AssertionResults(response: OutcomeDisplay.Response) {
    val summary = response.assertionSummary ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            summary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            color = if (response.hasFailedAssertions) MaterialTheme.colorScheme.error else PassColor,
        )
        SelectionContainer {
            Column {
                response.assertions.forEach {
                    Text(it.label, style = mono, color = if (it.passed) PassColor else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private val PassColor = Color(0xFF2E7D32)

@Composable
private fun StatusBadge(response: OutcomeDisplay.Response) {
    val color = when (response.statusClass) {
        StatusClass.SUCCESS -> Color(0xFF2E7D32)
        StatusClass.REDIRECT -> Color(0xFFEF6C00)
        StatusClass.CLIENT_ERROR -> Color(0xFFC62828)
        else -> Color(0xFF6A1B9A)
    }
    Surface(color = color, shape = RoundedCornerShape(4.dp)) {
        Text(response.statusLabel, color = Color.White, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun JsonRowView(row: JsonRow, c: JsonColors, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (row.toggle) it.clickable(onClick = onToggle) else it }.padding(start = (row.depth * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            when {
                !row.toggle -> ""
                row.kind == JsonKind.COLLAPSED -> "▸"
                else -> "▾"
            },
            style = mono, color = c.muted, modifier = Modifier.width(14.dp),
        )
        Text(rowText(row, c), style = mono)
    }
}

private fun rowText(row: JsonRow, c: JsonColors): AnnotatedString = buildAnnotatedString {
    row.key?.let {
        withStyle(SpanStyle(color = c.key)) { append("\"$it\"") }
        withStyle(SpanStyle(color = c.punctuation)) { append(": ") }
    }
    val valueColor = when (row.kind) {
        JsonKind.STRING -> c.string
        JsonKind.NUMBER -> c.number
        JsonKind.BOOLEAN, JsonKind.NULL -> c.keyword
        else -> c.punctuation
    }
    withStyle(SpanStyle(color = valueColor)) { append(row.text) }
    row.summary?.let { withStyle(SpanStyle(color = c.muted)) { append("  $it") } }
    if (row.comma) withStyle(SpanStyle(color = c.punctuation)) { append(",") }
}

private fun copyText(resp: OutcomeDisplay.Response, ui: OutcomeUi): String = when (ui.tab) {
    Tab.PREVIEW -> ""   // an image has no text to copy
    Tab.HEADERS -> resp.headers.joinToString("\n") { (name, value) -> "$name: $value" }
    Tab.BODY -> if (ui.raw) resp.rawBody else resp.prettyBody
}

private fun copy(text: String) =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

/** Asks where to save the body of [ok] (a modal dialog, so on the calling thread); null when it was cancelled. */
private fun chooseSavePath(ok: RunOutcome.Success): Path? {
    val r = ok.response
    val dialog = FileDialog(null as Frame?, "Save response body", FileDialog.SAVE)
    dialog.file = suggestedFileName(ok.request.url, r.header("Content-Disposition"), r.header("Content-Type"))
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return Path.of(dialog.directory, name)
}

/**
 * Writes [bytes] exactly to [path] and says so; a failure is the message, not an exception. The bytes go to a temporary
 * file next to [path] first and replace it only when complete, so a failed write cannot destroy an existing file
 * (its permissions are kept).
 */
internal fun writeBody(path: Path, bytes: ByteArray): String {
    var temp: Path? = null
    return try {
        val dir = path.toAbsolutePath().parent
        temp = Files.createTempFile(dir, ".save-", ".tmp")
        Files.write(temp, bytes)
        if (Files.exists(path)) runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path)) }
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        temp = null
        "Saved ${bytes.size} bytes to $path"
    } catch (e: Exception) {
        "Could not save the body: ${e.message ?: e::class.simpleName}"
    } finally {
        temp?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

/** The result of decoding an image for the preview. */
internal sealed interface DecodedImage {
    class Ok(val bitmap: ImageBitmap, val width: Int, val height: Int) : DecodedImage
    class Failed(val reason: String) : DecodedImage
}

/** Decodes [bytes] with Skia, refusing a body or an image that is too large first; never throws, not even when memory runs out. Blocking: call off the UI thread. */
internal fun decodeImage(bytes: ByteArray): DecodedImage = try {
    bodyRefusal(bytes.size)?.let { return DecodedImage.Failed(it) }
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    try {
        previewRefusal(bytes.size, image.width, image.height)?.let { DecodedImage.Failed(it) }
            ?: DecodedImage.Ok(image.toComposeImageBitmap(), image.width, image.height)
    } finally { image.close() }
} catch (e: OutOfMemoryError) {
    DecodedImage.Failed("there is not enough memory to decode the image")
} catch (e: Exception) {
    DecodedImage.Failed("the image could not be decoded (${e.message ?: e::class.simpleName})")
}

/**
 * The image of a response body, scaled down to fit the pane; a body that cannot be shown says why. It is decoded once
 * and kept on [ui]. A body that was cut short (a stored response) is not decoded: the picture would be incomplete.
 */
@Composable
private fun ImagePreview(ui: OutcomeUi, source: ImageSource, bodyCutNote: String?) {
    if (bodyCutNote != null) {
        Text(
            "No preview: the stored body was cut short, so the image is incomplete ($bodyCutNote). Save body writes the part that was kept.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("image-failed"),
        )
        return
    }
    LaunchedEffect(ui) {
        if (ui.decoded == null) ui.decoded = withContext(Dispatchers.Default) { decodeImage(source.bytes) }
    }
    when (val d = ui.decoded) {
        null -> Text("Decoding the image…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        is DecodedImage.Failed -> Text(
            "No preview: ${d.reason}. The Body tab shows the raw bytes; Save body writes them to a file.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("image-failed"),
        )
        is DecodedImage.Ok -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                imageInfoLabel(source.format, d.width, d.height, source.bytes.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.testTag("image-info"),
            )
            Image(
                d.bitmap, contentDescription = "Response image", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("image-preview"),
            )
        }
    }
}
