package com.dan323.httpclient.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpTokenKind

/** Colors for each kind of `.http` token; one palette per theme. */
class HighlightPalette(
    val method: Color, val url: Color, val headerName: Color, val variable: Color,
    val comment: Color, val separator: Color, val fileRef: Color,
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

fun highlight(text: String, palette: HighlightPalette): AnnotatedString = buildAnnotatedString {
    append(text)
    for (t in HttpLineLexer.tokenize(text)) palette.styleOf(t.kind)?.let { addStyle(it, t.start, t.end) }
}

/** Colors the text as it is typed; the text itself is unchanged so offsets map one to one. */
class HttpVisualTransformation(private val palette: HighlightPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlight(text.text, palette), OffsetMapping.Identity)
}
