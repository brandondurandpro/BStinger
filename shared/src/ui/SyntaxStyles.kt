package org.example.kotlineditor.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import org.example.kotlineditor.syntax.SyntaxColor

private val syntaxStyles: List<SpanStyle> =
    SyntaxColor.entries.map { color ->
        when (color) {
            SyntaxColor.Comment -> SpanStyle(color = IdeColors.Comment, fontStyle = FontStyle.Italic)
            SyntaxColor.Keyword -> SpanStyle(color = IdeColors.Keyword)
            SyntaxColor.StringLiteral -> SpanStyle(color = IdeColors.StringLiteral)
            SyntaxColor.StringEscape -> SpanStyle(color = IdeColors.StringEscape)
            SyntaxColor.Number -> SpanStyle(color = IdeColors.Number)
            SyntaxColor.Annotation -> SpanStyle(color = IdeColors.Annotation)
            SyntaxColor.Declaration -> SpanStyle(color = IdeColors.FunctionDeclaration)
            SyntaxColor.Property -> SpanStyle(color = IdeColors.Property)
            SyntaxColor.Constant -> SpanStyle(color = IdeColors.Property, fontStyle = FontStyle.Italic)
            SyntaxColor.TypeParameter -> SpanStyle(color = IdeColors.TypeParameter)
            SyntaxColor.Label -> SpanStyle(color = IdeColors.Label)
            SyntaxColor.MarkupTag -> SpanStyle(color = IdeColors.MarkupTag)
            SyntaxColor.MarkupAttribute -> SpanStyle(color = IdeColors.MarkupAttribute)
            SyntaxColor.Link -> SpanStyle(color = IdeColors.Link, textDecoration = TextDecoration.Underline)
            SyntaxColor.Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
            SyntaxColor.DiffAdded -> SpanStyle(color = IdeColors.GitAdded)
            SyntaxColor.DiffDeleted -> SpanStyle(color = IdeColors.GitDeleted)
        }
    }

/** The text style of a syntax color, by its ordinal (as `LineSpans.styles` stores it). */
fun syntaxStyle(ordinal: Int): SpanStyle = syntaxStyles[ordinal]

/** The text style of [color]. */
fun syntaxStyle(color: SyntaxColor): SpanStyle = syntaxStyles[color.ordinal]
