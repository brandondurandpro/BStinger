package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.Node
import io.github.treesitter.jtreesitter.Query
import io.github.treesitter.jtreesitter.QueryMatch
import java.util.regex.Pattern

/**
 * The text predicates of a highlight query (`#eq?`, `#match?`, `#any-of?` and their `not-` and `any-` forms), read
 * from its source and checked against the document's lines.
 *
 * jtreesitter only checks them on a tree parsed from a `String`, of which it then keeps a copy. The trees here are
 * parsed from the lines themselves (see [TreeSitterHighlighter]) and carry no text, so the predicates are checked
 * here, as jtreesitter would. The others (`#set!`, `#is?`, unknown ones) always hold, as there.
 */
internal class QueryPredicates private constructor(private val byPattern: Array<List<TextPredicate>>) {
    /** Whether the predicates of [match] hold, [textOf] giving the text between two character offsets. */
    fun hold(match: QueryMatch, textOf: (start: Int, end: Int) -> String): Boolean {
        val predicates = byPattern.getOrNull(match.patternIndex()).orEmpty()
        if (predicates.isEmpty()) return true
        val text = { node: Node -> textOf(node.startByte / 2, node.endByte / 2) }
        return predicates.all { it.test(match, text) }
    }

    companion object {
        /** The predicates of each pattern of [query], whose source is [source]. */
        fun read(query: Query, source: String): QueryPredicates {
            val bytes = source.toByteArray(Charsets.UTF_8)
            val patterns =
                Array(query.patternCount) { index ->
                    val start = query.startByteForPattern(index)
                    val end = query.endByteForPattern(index)
                    val pattern = String(bytes, start, end - start, Charsets.UTF_8)
                    QueryTokens(pattern).predicates().mapNotNull(TextPredicate::from)
                }
            return QueryPredicates(patterns)
        }
    }
}

/** One text predicate on the nodes of [capture]. */
private sealed class TextPredicate(val capture: String, val positive: Boolean, val any: Boolean) {
    abstract fun accepts(text: String, match: QueryMatch, textOf: (Node) -> String): Boolean

    open fun test(match: QueryMatch, textOf: (Node) -> String): Boolean {
        val nodes = match.findNodes(capture)
        val check = { node: Node -> accepts(textOf(node), match, textOf) == positive }
        return when {
            nodes.isEmpty() -> !positive
            any -> nodes.any(check)
            else -> nodes.all(check)
        }
    }

    /** `#eq? @capture "value"`. */
    class EqText(capture: String, val value: String, positive: Boolean, any: Boolean) :
        TextPredicate(capture, positive, any) {
        override fun accepts(text: String, match: QueryMatch, textOf: (Node) -> String) = text == value
    }

    /** `#eq? @capture @other`: as jtreesitter, a node passes when one node of the other capture compares as asked. */
    class EqCapture(capture: String, val other: String, positive: Boolean, any: Boolean) :
        TextPredicate(capture, positive, any) {
        override fun accepts(text: String, match: QueryMatch, textOf: (Node) -> String) = true

        override fun test(match: QueryMatch, textOf: (Node) -> String): Boolean {
            val others = match.findNodes(other).map(textOf)
            val check = { node: Node ->
                val text = textOf(node)
                others.any { other -> other == text == positive }
            }
            val nodes = match.findNodes(capture)
            return if (any) nodes.any(check) else nodes.all(check)
        }
    }

    /** `#match? @capture "regex"`, found anywhere in the text. */
    class Matches(capture: String, val regex: Pattern, positive: Boolean, any: Boolean) :
        TextPredicate(capture, positive, any) {
        override fun accepts(text: String, match: QueryMatch, textOf: (Node) -> String) = regex.matcher(text).find()
    }

    /** `#any-of? @capture "a" "b"…`. */
    class AnyOf(capture: String, val values: Set<String>, positive: Boolean) :
        TextPredicate(capture, positive, any = false) {
        override fun accepts(text: String, match: QueryMatch, textOf: (Node) -> String) = text in values

        // As jtreesitter: holds when every captured node is (or, for `not-any-of?`, is not) one of the values.
        override fun test(match: QueryMatch, textOf: (Node) -> String): Boolean =
            match.findNodes(capture).all { accepts(textOf(it), match, textOf) == positive }
    }

    companion object {
        private val EQ = setOf("eq?", "not-eq?", "any-eq?", "any-not-eq?")
        private val MATCH = setOf("match?", "not-match?", "any-match?", "any-not-match?")
        private val ANY_OF = setOf("any-of?", "not-any-of?")

        /** The predicate of the arguments [args] (its name first), `null` when it is not a text predicate. */
        fun from(args: List<QueryArg>): TextPredicate? {
            val name = (args.firstOrNull() as? QueryArg.Literal)?.value
            val capture = (args.getOrNull(1) as? QueryArg.Capture)?.name
            val values = args.drop(2)
            val value = values.singleOrNull()
            val positive = name?.contains("not-") == false
            val any = name?.startsWith("any-") == true
            val literals = values.filterIsInstance<QueryArg.Literal>().map { it.value }
            return when {
                capture == null -> null
                name in EQ && value is QueryArg.Capture -> EqCapture(capture, value.name, positive, any)
                name in EQ && value is QueryArg.Literal -> EqText(capture, value.value, positive, any)
                name in MATCH && value is QueryArg.Literal -> Matches(capture, value.value.toPattern(), positive, any)
                name in ANY_OF -> AnyOf(capture, literals.toSet(), positive)
                else -> null
            }
        }
    }
}

/** An argument of a predicate: `@name` or a string (quoted or not). */
internal sealed interface QueryArg {
    data class Capture(val name: String) : QueryArg

    data class Literal(val value: String) : QueryArg
}

/** Reads the predicates `(#name? @capture "value" …)` of the source of one pattern, skipping strings and comments. */
internal class QueryTokens(private val text: String) {
    private var i = 0

    /** The arguments of each predicate, its name first. */
    fun predicates(): List<List<QueryArg>> {
        val found = ArrayList<List<QueryArg>>()
        while (i < text.length) {
            when (text[i]) {
                ';' -> {
                    skipComment()
                }

                '"' -> {
                    readString()
                }

                '(' -> {
                    i++
                    skipBlanks()
                    if (i < text.length && text[i] == '#') {
                        i++
                        found += readPredicate()
                    }
                }

                else -> {
                    i++
                }
            }
        }
        return found
    }

    // After `(#`: the name, then the arguments until `)`.
    private fun readPredicate(): List<QueryArg> {
        val args = mutableListOf<QueryArg>(QueryArg.Literal(readWord()))
        var open = true
        while (open) {
            skipBlanks()
            open = i < text.length && text[i] != ')'
            if (open) readArgument()?.let(args::add)
        }
        i++
        return args
    }

    private fun readArgument(): QueryArg? =
        when (text[i]) {
            ';' -> {
                skipComment()
                null
            }

            '"' -> {
                QueryArg.Literal(readString())
            }

            '@' -> {
                i++
                QueryArg.Capture(readWord())
            }

            else -> {
                // A word, or a character that cannot start one (a stray bracket): skipped.
                val word = readWord()
                if (word.isEmpty()) i++
                word.takeIf { it.isNotEmpty() }?.let(QueryArg::Literal)
            }
        }

    private fun readWord(): String {
        val start = i
        while (i < text.length && isQueryWordChar(text[i])) i++
        return text.substring(start, i)
    }

    // A string from its opening quote, with the escapes of tree-sitter: \n \r \t \0, else the escaped character.
    private fun readString(): String {
        val value = StringBuilder()
        i++
        while (i < text.length && text[i] != '"') {
            val escaped = text[i] == '\\' && i + 1 < text.length
            if (escaped) i++
            value.append(if (escaped) unescape(text[i]) else text[i])
            i++
        }
        i++
        return value.toString()
    }

    private fun unescape(c: Char): Char =
        when (c) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '0' -> '\u0000'
            else -> c
        }

    private fun skipComment() {
        while (i < text.length && text[i] != '\n') i++
    }

    private fun skipBlanks() {
        while (i < text.length && text[i].isWhitespace()) i++
    }
}

private fun isQueryWordChar(c: Char): Boolean = !c.isWhitespace() && c !in "()\"@;"
