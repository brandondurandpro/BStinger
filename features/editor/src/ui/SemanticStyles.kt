package org.example.kotlineditor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import org.example.kotlineditor.model.SemanticToken
import org.example.kotlineditor.state.visualColumn

// The semantic tokens of the language server over the colors of tree-sitter: what a word is (a class, a parameter,
// a static method) rather than how it is written.

private val TYPE_NAMES = setOf("class", "struct", "type", "enum", "namespace")
private val CALLABLE_NAMES = setOf("function", "method", "macro")

/** The styles of the semantic tokens of one line, in characters of the line. */
@Immutable
class LineSemantic(val starts: IntArray, val ends: IntArray, val styles: List<SpanStyle>) {
    override fun equals(other: Any?): Boolean {
        val same = other as? LineSemantic ?: return false
        val samePlaces = starts.contentEquals(same.starts) && ends.contentEquals(same.ends)
        return samePlaces && styles == same.styles
    }

    override fun hashCode(): Int = 31 * (31 * starts.contentHashCode() + ends.contentHashCode()) + styles.hashCode()
}

/**
 * The style of a token of [type] with [modifiers]: its color by type, italic when static (or an interface),
 * struck through when deprecated; null for what tree-sitter already colors (keywords, strings, comments...).
 */
fun semanticStyle(type: String, modifiers: Set<String>): SpanStyle? {
    val declaration = "declaration" in modifiers || "definition" in modifiers
    val color =
        when (type) {
            in TYPE_NAMES, "interface" -> IdeColors.SemanticType
            "typeParameter" -> IdeColors.TypeParameter
            in CALLABLE_NAMES -> if (declaration) IdeColors.FunctionDeclaration else IdeColors.SemanticCall
            "parameter" -> IdeColors.SemanticParameter
            "property", "enumMember", "event" -> IdeColors.Property
            else -> null
        }
    val italic = "static" in modifiers || type == "interface"
    val struck = "deprecated" in modifiers
    return color?.let {
        SpanStyle(
            color = it,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        )
    }
}

/** The semantic styles of each line, from the tokens of the document. */
internal fun semanticByLine(tokens: List<SemanticToken>): Map<Int, LineSemantic> {
    val byLine = tokens.groupBy { it.line }.mapValues { (_, onLine) -> lineSemantic(onLine) }
    return byLine.filterValues { it.styles.isNotEmpty() }
}

private fun lineSemantic(onLine: List<SemanticToken>): LineSemantic {
    val styled = onLine.mapNotNull { token -> semanticStyle(token.type, token.modifiers)?.let { token to it } }
    val starts = IntArray(styled.size) { styled[it].first.column }
    val ends = IntArray(styled.size) { styled[it].first.column + styled[it].first.length }
    return LineSemantic(starts, ends, styled.map { it.second })
}

/** [text], the displayed [line] (tabs expanded), with the styles of [semantic] over its own. */
internal fun withSemantic(text: AnnotatedString, line: String, semantic: LineSemantic?): AnnotatedString {
    if (semantic == null) return text
    val builder = AnnotatedString.Builder(text)
    for (index in semantic.styles.indices) {
        val start = visualColumn(line, semantic.starts[index]).coerceAtMost(text.length)
        val end = visualColumn(line, semantic.ends[index]).coerceAtMost(text.length)
        if (end > start) builder.addStyle(semantic.styles[index], start, end)
    }
    return builder.toAnnotatedString()
}
