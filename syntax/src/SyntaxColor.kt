package org.example.kotlineditor.syntax

/**
 * What a highlighted range is, for its color: the line lexer's tokens and tree-sitter's captures both map to these.
 * The UI turns them into text styles (`syntaxStyle` in the theme); this module knows no colors.
 */
enum class SyntaxColor {
    Comment,
    Keyword,
    StringLiteral,
    StringEscape,
    Number,
    Annotation,
    Declaration,
    Property,
    Constant,
    TypeParameter,
    Label,
    MarkupTag,
    MarkupAttribute,
    Link,
    Emphasis,
    DiffAdded,
    DiffDeleted,
}
