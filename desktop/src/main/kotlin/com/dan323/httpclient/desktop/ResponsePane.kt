package com.dan323.httpclient.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.viewmodel.JsonKind
import com.dan323.httpclient.viewmodel.JsonRow
import com.dan323.httpclient.viewmodel.StatusClass
import com.dan323.httpclient.viewmodel.OutcomeDisplay
import com.dan323.httpclient.viewmodel.display
import com.dan323.httpclient.viewmodel.flattenJson
import com.dan323.httpclient.viewmodel.parseJsonContainer
import kotlinx.serialization.json.JsonElement
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal enum class Tab { BODY, HEADERS }

/** What the user has toggled for one response: tab, raw/pretty, and which JSON nodes are collapsed. */
internal class OutcomeUi(val json: JsonElement?) {
    var tab by mutableStateOf(Tab.BODY)
    var raw by mutableStateOf(false)
    val collapsed = mutableStateSetOf<String>()
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
        val json = (d as? OutcomeDisplay.Response)?.let { r ->
            try { parseJson(r.rawBody) } catch (e: Exception) { null }   // unparsable: show the raw body
        }
        OutcomeEntry(d, (d as? OutcomeDisplay.Response)?.let { OutcomeUi(json) })
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

@Composable
fun ResponsePane(outcomes: List<RunOutcome>, modifier: Modifier = Modifier) {
    val colors = if (isSystemInDarkTheme()) JsonColors.Dark else JsonColors.Light
    val models = remember { OutcomeModels() }
    LaunchedEffect(outcomes) { models.update(outcomes) }
    // Read the collapsed sets here, in composition, so toggling a node recomposes the list.
    val sections = outcomes.mapNotNull { o ->
        val entry = models[o] ?: return@mapNotNull null
        val ui = entry.ui
        val rows = if (ui?.json != null && ui.tab == Tab.BODY && !ui.raw) flattenJson(ui.json, ui.collapsed) else null
        Triple(entry.display, ui, rows)
    }

    LazyColumn(modifier) {
        for ((outcome, ui, rows) in sections) {
            item { OutcomeHeader(outcome, ui) }
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
                    rows != null -> items(rows) { row -> JsonRowView(row, colors) { ui.toggle(row.path) } }
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

private fun OutcomeUi.toggle(path: String) { if (!collapsed.remove(path)) collapsed.add(path) }

@Composable
private fun OutcomeHeader(outcome: OutcomeDisplay, ui: OutcomeUi?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(outcome.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        when (outcome) {
            is OutcomeDisplay.Error -> Text("Error: ${outcome.message}", color = MaterialTheme.colorScheme.error)
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
                if (ui != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(ui.tab == Tab.BODY, { ui.tab = Tab.BODY }, label = { Text("Body") })
                        FilterChip(ui.tab == Tab.HEADERS, { ui.tab = Tab.HEADERS }, label = { Text("Headers (${outcome.headers.size})") })
                        Box(Modifier.weight(1f))
                        if (ui.tab == Tab.BODY && ui.json != null) {
                            FilterChip(!ui.raw, { ui.raw = false }, label = { Text("Pretty") })
                            FilterChip(ui.raw, { ui.raw = true }, label = { Text("Raw") })
                        }
                        OutlinedButton(onClick = { copy(copyText(outcome, ui)) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

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
    Tab.HEADERS -> resp.headers.joinToString("\n") { (name, value) -> "$name: $value" }
    Tab.BODY -> if (ui.raw) resp.rawBody else resp.prettyBody
}

private fun copy(text: String) =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
