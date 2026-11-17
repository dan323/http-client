package com.dan323.httpclient.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.Severity
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpTokenKind

/** Colors for each kind of `.http` token; one palette per theme. */
class HighlightPalette(
    val method: Color, val url: Color, val headerName: Color, val variable: Color,
    val comment: Color, val separator: Color, val fileRef: Color,
    val error: Color = Color(0xFFC62828), val warning: Color = Color(0xFFB26A00),
    val match: Color = Color(0x66FFD54F), val currentMatch: Color = Color(0xCCFF9800),
) {
    fun styleOf(kind: HttpTokenKind): SpanStyle? = when (kind) {
        HttpTokenKind.METHOD -> SpanStyle(color = method, fontWeight = FontWeight.Bold)
        HttpTokenKind.URL -> SpanStyle(color = url)
        HttpTokenKind.HEADER_NAME -> SpanStyle(color = headerName)
        HttpTokenKind.VARIABLE -> SpanStyle(color = variable, fontWeight = FontWeight.SemiBold)
        HttpTokenKind.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
        HttpTokenKind.SEPARATOR -> SpanStyle(color = separator, fontWeight = FontWeight.Bold)
        HttpTokenKind.FILE_REF -> SpanStyle(color = fileRef)
        else -> null
    }

    companion object {
        val Light = HighlightPalette(
            method = Color(0xFF0033B3), url = Color(0xFF067D17), headerName = Color(0xFF871094),
            variable = Color(0xFFC77D00), comment = Color(0xFF8C8C8C), separator = Color(0xFF6A6A6A), fileRef = Color(0xFF00627A),
        )
        val Dark = HighlightPalette(
            method = Color(0xFF6C95EB), url = Color(0xFF6AAB73), headerName = Color(0xFFC77DBB),
            variable = Color(0xFFE0A030), comment = Color(0xFF7A7E85), separator = Color(0xFF9A9DA3), fileRef = Color(0xFF56A8C0),
        )
    }
}

/**
 * Colors the tokens of [text], underlines each of [problems] (red for errors, amber for warnings) and marks the find
 * [matches] with a background, [current] (one of them) stronger.
 */
fun highlight(
    text: String, palette: HighlightPalette, problems: List<Diagnostic> = emptyList(),
    matches: List<IntRange> = emptyList(), current: IntRange? = null,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (m in matches) {
        val start = m.first.coerceIn(0, text.length)
        val end = (m.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(background = if (m == current) palette.currentMatch else palette.match), start, end)
    }
    for (t in HttpLineLexer.tokenize(text)) palette.styleOf(t.kind)?.let { addStyle(it, t.start, t.end) }
    for (d in problems) {
        val start = d.range.offset.coerceIn(0, text.length)
        val end = d.range.end.coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = if (d.severity == Severity.ERROR) palette.error else palette.warning), start, end)
    }
}

/** Colors the text as it is typed; the text itself is unchanged so offsets map one to one. */
class HttpVisualTransformation(
    private val palette: HighlightPalette, private val problems: List<Diagnostic> = emptyList(),
    private val matches: List<IntRange> = emptyList(), private val current: IntRange? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlight(text.text, palette, problems, matches, current), OffsetMapping.Identity)
}

/** `METHOD url` for the request list, with the method coloured like in the editor. */
fun requestSummary(method: String, url: String, palette: HighlightPalette): AnnotatedString = buildAnnotatedString {
    withStyle(palette.styleOf(HttpTokenKind.METHOD)!!) { append(method) }
    append(" ")
    append(url)
}
