package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.Node

/** Code of [language] between the characters [start] and [end] of a document, inside another language's. */
internal class Injection(val language: String, val start: Int, val end: Int)

/** Where a `.vue` file holds code besides its markup: the script, the style, the expressions of the template. */
internal object VueInjections {
    private const val RAW_TEXT = "raw_text"
    private const val ATTRIBUTE_VALUE = "attribute_value"
    private const val TYPESCRIPT = "typescript"
    private const val JAVASCRIPT = "javascript"
    private val TSX_TAG = Regex("""lang\s*=\s*["']tsx["']""")
    private val TS_TAG = Regex("""lang\s*=\s*["']ts["']""")

    /** The injections of the tree under [root]; [textOf] reads the document between two character offsets. */
    fun find(root: Node, textOf: (Int, Int) -> String): List<Injection> {
        val found = ArrayList<Injection>()
        collect(root, textOf, found)
        return found
    }

    private fun collect(node: Node, textOf: (Int, Int) -> String, found: MutableList<Injection>) {
        if (node.type == RAW_TEXT || node.type == ATTRIBUTE_VALUE) return
        for (child in node.children) {
            val language = languageInside(node, child, textOf)
            if (language != null) {
                found += Injection(language, child.startByte / 2, child.endByte / 2)
            } else {
                collect(child, textOf, found)
            }
        }
    }

    // What the [child] of [parent] holds, when it is code of another language.
    private fun languageInside(parent: Node, child: Node, textOf: (Int, Int) -> String): String? =
        when {
            child.type == RAW_TEXT && parent.type == "script_element" -> scriptLanguage(parent, textOf)
            child.type == RAW_TEXT && parent.type == "style_element" -> "css"
            child.type == RAW_TEXT && parent.type == "interpolation" -> TYPESCRIPT
            child.type == ATTRIBUTE_VALUE && isDirectiveValue(parent) -> TYPESCRIPT
            else -> null
        }

    private fun isDirectiveValue(quoted: Node): Boolean {
        val inDirective = quoted.parent.map { it.type == "directive_attribute" }.orElse(false)
        return quoted.type == "quoted_attribute_value" && inDirective
    }

    // `<script lang="ts">` is TypeScript, `lang="tsx"` TSX, a script without a language JavaScript.
    private fun scriptLanguage(script: Node, textOf: (Int, Int) -> String): String {
        val start = script.children.firstOrNull { it.type == "start_tag" } ?: return JAVASCRIPT
        val tag = textOf(start.startByte / 2, start.endByte / 2)
        return when {
            TSX_TAG.containsMatchIn(tag) -> "tsx"
            TS_TAG.containsMatchIn(tag) -> TYPESCRIPT
            else -> JAVASCRIPT
        }
    }
}
