package org.example.kotlineditor.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import org.example.kotlineditor.syntax.SyntaxColor

@Suppress("CyclomaticComplexMethod")
private fun stylesOf(palette: IdePalette): List<SpanStyle> =
    SyntaxColor.entries.map { color ->
        when (color) {
            SyntaxColor.Comment -> SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
            SyntaxColor.Keyword -> SpanStyle(color = palette.keyword)
            SyntaxColor.StringLiteral -> SpanStyle(color = palette.stringLiteral)
            SyntaxColor.StringEscape -> SpanStyle(color = palette.stringEscape)
            SyntaxColor.Number -> SpanStyle(color = palette.number)
            SyntaxColor.Annotation -> SpanStyle(color = palette.annotation)
            SyntaxColor.Declaration -> SpanStyle(color = palette.functionDeclaration)
            SyntaxColor.Property -> SpanStyle(color = palette.property)
            SyntaxColor.Constant -> SpanStyle(color = palette.property, fontStyle = FontStyle.Italic)
            SyntaxColor.TypeParameter -> SpanStyle(color = palette.typeParameter)
            SyntaxColor.Label -> SpanStyle(color = palette.label)
            SyntaxColor.MarkupTag -> SpanStyle(color = palette.markupTag)
            SyntaxColor.MarkupAttribute -> SpanStyle(color = palette.markupAttribute)
            SyntaxColor.Link -> SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
            SyntaxColor.Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
            SyntaxColor.DiffAdded -> SpanStyle(color = palette.gitAdded)
            SyntaxColor.DiffDeleted -> SpanStyle(color = palette.gitDeleted)
        }
    }

// The styles of the palette last used: a theme change builds them again, and the lookup stays a read and a compare.
private var cached: Pair<IdePalette, List<SpanStyle>>? = null

private fun currentStyles(): List<SpanStyle> {
    val palette = IdeColors.palette
    val known = cached
    if (known != null && known.first === palette) return known.second
    val built = stylesOf(palette)
    cached = palette to built
    return built
}

/** The text style of a syntax color, by its ordinal (as `LineSpans.styles` stores it), in the theme in use. */
fun syntaxStyle(ordinal: Int): SpanStyle = currentStyles()[ordinal]

/** The text style of [color], in the theme in use. */
fun syntaxStyle(color: SyntaxColor): SpanStyle = currentStyles()[color.ordinal]
