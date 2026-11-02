package org.example.kotlineditor.syntax.treesitter

import org.example.kotlineditor.syntax.SyntaxColor

/**
 * Colors ([SyntaxColor]) of the captures of highlight queries. Queries name captures from the generic (`keyword`) to
 * the specific (`keyword.control.conditional`), and differ by source (the grammars' own, Helix's): a name takes the
 * style of its longest known prefix. Captures without style (`variable`, `punctuation`, `operator`...) keep the text
 * color.
 */
internal object CaptureStyles {
    const val NONE = -1
    const val IGNORED = -2

    private val comment = SyntaxColor.Comment
    private val keyword = SyntaxColor.Keyword
    private val string = SyntaxColor.StringLiteral
    private val escape = SyntaxColor.StringEscape
    private val number = SyntaxColor.Number
    private val annotation = SyntaxColor.Annotation
    private val function = SyntaxColor.Declaration
    private val property = SyntaxColor.Property
    private val constant = SyntaxColor.Constant
    private val typeParameter = SyntaxColor.TypeParameter
    private val label = SyntaxColor.Label
    private val tag = SyntaxColor.MarkupTag
    private val attribute = SyntaxColor.MarkupAttribute
    private val link = SyntaxColor.Link
    private val italic = SyntaxColor.Emphasis

    private val byName: Map<String, SyntaxColor?> =
        mapOf(
            "comment" to comment,
            "keyword" to keyword,
            "conditional" to keyword,
            "repeat" to keyword,
            "include" to keyword,
            "exception" to keyword,
            "storageclass" to keyword,
            "boolean" to keyword,
            "constant.builtin" to keyword,
            "variable.builtin" to keyword,
            "type.builtin" to keyword,
            "string" to string,
            "character" to string,
            "string.escape" to escape,
            "escape" to escape,
            "string.special" to escape,
            "string.special.path" to string,
            "string.special.url" to link,
            "string.special.symbol" to constant,
            "string.special.key" to property,
            "punctuation.special" to escape,
            "number" to number,
            "float" to number,
            "constant.numeric" to number,
            "constant.character" to string,
            "constant.character.escape" to escape,
            "attribute" to annotation,
            "annotation" to annotation,
            "decorator" to annotation,
            "function" to function,
            "method" to function,
            "constructor" to null,
            "function.macro" to annotation,
            "property" to property,
            "field" to property,
            "variable.member" to property,
            "variable.other.member" to property,
            "constant" to constant,
            "type.parameter" to typeParameter,
            "label" to label,
            "tag" to tag,
            "tag.attribute" to attribute,
            "tag.builtin" to tag,
            "markup.heading" to function,
            "markup.italic" to italic,
            "markup.link" to link,
            "markup.link.text" to string,
            "markup.raw" to string,
            "markup.list" to keyword,
            "markup.quote" to comment,
            "text.title" to function,
            "text.emphasis" to italic,
            "text.uri" to link,
            "text.reference" to link,
            "text.literal" to string,
            "diff.plus" to SyntaxColor.DiffAdded,
            "diff.minus" to SyntaxColor.DiffDeleted,
        )

    // Captures that are not highlights: helpers of predicates (`@_name`) and other editors' markers.
    private val ignored = setOf("spell", "nospell", "conceal", "local", "injection")

    /**
     * Ordinal of the [SyntaxColor] of capture [name] (without `@`): [NONE] when it keeps the text color (it still
     * takes the range over the captures of later patterns), [IGNORED] when it is not a highlight at all.
     */
    fun styleOf(name: String): Int {
        if (name.startsWith("_") || name.substringBefore('.') in ignored) return IGNORED
        // The longest prefix (`a.b.c`, `a.b`, `a`) that has an entry, even a null one.
        val known =
            generateSequence(name) { prefix -> prefix.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } }
                .firstOrNull { byName.containsKey(it) }
        return known?.let(byName::get)?.ordinal ?: NONE
    }
}
