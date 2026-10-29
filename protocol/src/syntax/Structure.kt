package org.example.kotlineditor.syntax

/** Kind of a [Declaration], with the letter shown in its badge. */
enum class DeclarationKind(
    /** Letter of the badge shown in the popups. */
    val badge: String,
) {
    /** A class, data class, enum or type alias. */
    Class("C"),

    /** An interface. */
    Interface("I"),

    /** A Kotlin object. */
    Object("O"),

    /** A function or method. */
    Function("f"),

    /** A top-level or member property. */
    Property("p"),
}

/** One entry of the File Structure popup (Ctrl+F12). [depth] comes from the indentation. */
data class Declaration(
    /** Declared name. */
    val name: String,
    /** What is declared. */
    val kind: DeclarationKind,
    /** Zero-based line of the declaration. */
    val line: Int,
    /** Zero-based column of the name. */
    val column: Int,
    /** Nesting level, from the indentation. */
    val depth: Int,
)

private const val MODIFIERS =
    """(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|enum|inner|""" +
        """override|suspend|inline|operator|infix|tailrec|external|const|lateinit|annotation|value|expect|actual|""" +
        """static|default|synchronized|native|@\w+(?:\([^)]*\))?)\s+)*"""
private const val COMMENT_END = "*/"
private const val INDENT = 4
private const val NAME_GROUP_OF_TYPE = 2
private const val NAME_GROUP_OF_METHOD = 1

private val KOTLIN_DECLARATION =
    Regex(
        """^\s*$MODIFIERS(class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?""" +
            """(?:[\w.]+(?:<[^>]*>)?\??\.)?(`[^`]+`|\w+)""",
    )
private val JAVA_TYPE = Regex("""^\s*$MODIFIERS(class|interface|enum|record)\s+(\w+)""")
private val JAVA_METHOD = Regex("""^\s*$MODIFIERS(?:<[^>]*>\s*)?[\w<>\[\],.? ]+\s+(\w+)\s*\([^;]*$""")
private val KOTLIN_KINDS =
    mapOf(
        "class" to DeclarationKind.Class,
        "typealias" to DeclarationKind.Class,
        "interface" to DeclarationKind.Interface,
        "object" to DeclarationKind.Object,
        "fun" to DeclarationKind.Function,
        "val" to DeclarationKind.Property,
        "var" to DeclarationKind.Property,
    )
private val CONTROL_FLOW = setOf("if", "for", "while", "switch", "catch", "return", "new", "else")
private val NOT_DECLARATIONS = listOf("//", "*", "import ", "package ")

/**
 * Lightweight, regex-based outline of Kotlin and Java sources: good enough for navigation, without a parser.
 * Lines inside block comments are skipped thanks to the precomputed comment states.
 */
object Structure {
    /** Declarations of [lines]; [commentStates] (see [blockCommentStates]) lets commented code be skipped. */
    fun outline(lines: List<String>, language: Language, commentStates: BooleanArray? = null): List<Declaration> =
        if (language != Language.Kotlin && language != Language.Java) {
            emptyList()
        } else {
            lines.withIndex().mapNotNull { (index, line) ->
                val commented = commentStates?.getOrNull(index) == true
                if (commented || isNotDeclaration(line)) null else declaration(line, index, language)
            }
        }

    /** `result[i]` is true when line `i` starts inside a block comment (strings are skipped). */
    fun blockCommentStates(lines: List<String>): BooleanArray {
        val states = BooleanArray(lines.size)
        var inComment = false
        lines.forEachIndexed { index, line ->
            states[index] = inComment
            inComment = endsInComment(line, inComment)
        }
        return states
    }

    private fun isNotDeclaration(line: String): Boolean {
        val trimmed = line.trimStart()
        return NOT_DECLARATIONS.any { trimmed.startsWith(it) }
    }

    private fun declaration(line: String, index: Int, language: Language): Declaration? {
        val depth = (line.length - line.trimStart().length) / INDENT
        return if (language == Language.Kotlin) kotlin(line, index, depth) else java(line, index, depth)
    }

    private fun kotlin(line: String, index: Int, depth: Int): Declaration? {
        val match = KOTLIN_DECLARATION.find(line) ?: return null
        val kind = KOTLIN_KINDS.getValue(match.groupValues[1])
        // Properties more than one level deep are locals inside functions, not members.
        val isLocal = kind == DeclarationKind.Property && depth > 1
        val name = checkNotNull(match.groups[NAME_GROUP_OF_TYPE])
        return if (isLocal) null else Declaration(name.value, kind, index, name.range.first, depth)
    }

    private fun java(line: String, index: Int, depth: Int): Declaration? {
        val type = JAVA_TYPE.find(line)
        val method = if (type == null) JAVA_METHOD.find(line)?.takeIf { it.groupValues[1] !in CONTROL_FLOW } else null
        val kind =
            when {
                type == null -> DeclarationKind.Function
                type.groupValues[1] == "interface" -> DeclarationKind.Interface
                else -> DeclarationKind.Class
            }
        val name = type?.groups?.get(NAME_GROUP_OF_TYPE) ?: method?.groups?.get(NAME_GROUP_OF_METHOD)
        return name?.let { Declaration(it.value, kind, index, it.range.first, depth) }
    }

    private fun endsInComment(line: String, startsInComment: Boolean): Boolean {
        var inComment = startsInComment
        var i = 0
        while (i < line.length) {
            if (inComment) {
                val end = line.indexOf(COMMENT_END, i)
                inComment = end < 0
                i = if (end < 0) line.length else end + COMMENT_END.length
            } else {
                val opens = line.startsWith("/*", i)
                inComment = opens
                i = if (opens) i + 2 else nextCodeIndex(line, i)
            }
        }
        return inComment
    }

    // Index after the code token at `i`: the rest of the line for `//`, after the closing quote for a string.
    private fun nextCodeIndex(line: String, i: Int): Int {
        val c = line[i]
        return when {
            line.startsWith("//", i) -> line.length
            c == '"' || c == '\'' -> stringEnd(line, i) + 1
            else -> i + 1
        }
    }

    private fun stringEnd(line: String, start: Int): Int {
        var j = start + 1
        while (j < line.length && line[j] != line[start]) j += if (line[j] == '\\') 2 else 1
        return j
    }
}
