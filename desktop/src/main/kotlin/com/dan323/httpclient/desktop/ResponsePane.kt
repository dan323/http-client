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
import com.dan323.httpclient.adapters.prettyJson
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.HttpResponse
import kotlinx.serialization.json.JsonElement
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class Tab { BODY, HEADERS }

/** What the user has toggled for one response: tab, raw/pretty, and which JSON nodes are collapsed. */
private class OutcomeUi(body: String) {
    var tab by mutableStateOf(Tab.BODY)
    var raw by mutableStateOf(false)
    val collapsed = mutableStateSetOf<String>()
    val json: JsonElement? = parseJsonContainer(body)
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
    val uis = remember { HashMap<RunOutcome, OutcomeUi>() }
    // Read the collapsed sets here, in composition, so toggling a node recomposes the list.
    val sections = outcomes.map { o ->
        val ui = (o as? RunOutcome.Success)?.let { s -> uis.getOrPut(o) { OutcomeUi(s.response.bodyAsString()) } }
        val rows = if (ui?.json != null && ui.tab == Tab.BODY && !ui.raw) flattenJson(ui.json, ui.collapsed) else null
        Triple(o, ui, rows)
    }

    LazyColumn(modifier) {
        for ((outcome, ui, rows) in sections) {
            item { OutcomeHeader(outcome, ui) }
            if (outcome is RunOutcome.Success && ui != null) {
                val resp = outcome.response
                when {
                    ui.tab == Tab.HEADERS -> items(resp.headers) { h ->
                        SelectionContainer {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)) { append(h.name) }
                                    append(": ")
                                    append(h.value)
                                },
                                style = mono, modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                    rows != null -> items(rows) { row -> JsonRowView(row, colors) { ui.toggle(row.path) } }
                    else -> item {
                        val text = resp.bodyAsString()
                        SelectionContainer {
                            Text(if (text.isEmpty()) "(empty body)" else if (ui.raw) text else prettyJson(text) ?: text, style = mono)
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
private fun OutcomeHeader(outcome: RunOutcome, ui: OutcomeUi?) {
    val r = outcome.request
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.name ?: "${r.method} ${r.url}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        when (outcome) {
            is RunOutcome.Failure -> Text("Error: ${outcome.message}", color = MaterialTheme.colorScheme.error)
            is RunOutcome.Success -> {
                val resp = outcome.response
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(resp.statusCode)
                    Text("${resp.durationMillis} ms", style = MaterialTheme.typography.bodySmall)
                    Text(sizeLabel(resp.body.size), style = MaterialTheme.typography.bodySmall)
                    resp.header("content-type")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
                if (ui != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(ui.tab == Tab.BODY, { ui.tab = Tab.BODY }, label = { Text("Body") })
                        FilterChip(ui.tab == Tab.HEADERS, { ui.tab = Tab.HEADERS }, label = { Text("Headers (${resp.headers.size})") })
                        Box(Modifier.weight(1f))
                        if (ui.tab == Tab.BODY && ui.json != null) {
                            FilterChip(!ui.raw, { ui.raw = false }, label = { Text("Pretty") })
                            FilterChip(ui.raw, { ui.raw = true }, label = { Text("Raw") })
                        }
                        OutlinedButton(onClick = { copy(copyText(resp, ui)) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: Int) {
    val color = when (status) {
        in 200..299 -> Color(0xFF2E7D32)
        in 300..399 -> Color(0xFFEF6C00)
        in 400..499 -> Color(0xFFC62828)
        else -> Color(0xFF6A1B9A)
    }
    Surface(color = color, shape = RoundedCornerShape(4.dp)) {
        Text("$status ${statusText(status)}".trim(), color = Color.White, fontWeight = FontWeight.Bold,
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

private fun copyText(resp: HttpResponse, ui: OutcomeUi): String {
    val body = resp.bodyAsString()
    return when (ui.tab) {
        Tab.HEADERS -> resp.headers.joinToString("\n") { "${it.name}: ${it.value}" }
        Tab.BODY -> if (ui.raw) body else prettyJson(body) ?: body
    }
}

private fun copy(text: String) =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

internal fun sizeLabel(bytes: Int) = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

internal fun statusText(code: Int) = when (code) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
    500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> ""
}
