package org.example.kotlineditor.testkit.lsp

import java.io.File
import java.net.URI

/** A declaration found in a PHP file by [FakePhpCode]. */
internal data class FakeDeclaration(
    val uri: String,
    val name: String,
    // LSP SymbolKind: 5 class, 6 method, 10 enum, 11 interface, 12 function.
    val kind: Int,
    val line: Int,
    // Column of the declared name.
    val column: Int,
    // Column where the declaration's text starts (its first word).
    val start: Int,
    // Last line of its body.
    val endLine: Int,
    val signature: String,
    val documentation: String?,
    val parent: String?,
)

/** An occurrence of a name in a file. */
internal data class FakeOccurrence(val uri: String, val line: Int, val column: Int, val length: Int)

/**
 * Just enough PHP understanding, with regular expressions, for the [FakeLanguageServer] to answer like phpactor:
 * declarations of classes, interfaces, functions and methods with their docblocks, whole-word occurrences, calls of
 * unknown functions. The files are the open documents and, for the others, those of the root directory on disk.
 */
internal class FakePhpCode(
    private val open: Map<String, List<String>>,
    private val rootUri: String?,
    // The files on disk as an indexing server knows them; null: read now.
    private val disk: Map<String, List<String>>? = null,
) {
    private val diskFiles: Map<String, List<String>> by lazy { disk ?: readDisk(rootUri) }

    /** Every file: the open ones as the client sent them, the others as on disk. */
    val files: Map<String, List<String>> by lazy { diskFiles + open }

    val declarations: List<FakeDeclaration> by lazy { files.flatMap { (uri, lines) -> declarationsOf(uri, lines) } }

    /** The word at [column] of [line] in [uri], `$` included, and where it starts. */
    fun wordAt(uri: String, line: Int, column: Int): Pair<String, Int>? {
        val text = files[uri]?.getOrNull(line) ?: return null
        var start = column.coerceIn(0, text.length)
        var end = start
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length && isWordChar(text[end])) end++
        if (start == end) return null
        return text.substring(start, end) to start
    }

    /** The declarations named [name]. */
    fun declarationsNamed(name: String): List<FakeDeclaration> = declarations.filter { it.name == name }

    /** The whole-word occurrences of [name]: in every file, or only in [uri] for a variable. */
    fun occurrences(name: String, uri: String): List<FakeOccurrence> {
        val pattern = if (name.startsWith("$")) Regex(Regex.escape(name) + "(?!\\w)") else Regex("(?<![\\w$])" + Regex.escape(name) + "(?!\\w)")
        val searched = if (name.startsWith("$")) files.filterKeys { it == uri } else files
        return searched.flatMap { (fileUri, lines) ->
            lines.flatMapIndexed { index, text ->
                // Comments and docblocks do not use the name.
                val trimmed = text.trimStart()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("#")
                val code = if (isComment) "" else text.substringBefore("//")
                pattern.findAll(code).map { FakeOccurrence(fileUri, index, it.range.first, name.length) }.toList()
            }
        }
    }

    /** Where a variable is first assigned in [uri]. */
    fun assignmentOf(name: String, uri: String): FakeOccurrence? {
        val lines = files[uri] ?: return null
        val pattern = Regex(Regex.escape(name) + "\\s*=[^=]")
        lines.forEachIndexed { index, text ->
            pattern.find(text)?.let { return FakeOccurrence(uri, index, it.range.first, name.length) }
        }
        return null
    }

    /** The class a variable of [uri] is created from (`$x = new Greeter()`). */
    fun typeOf(name: String, uri: String): String? {
        val lines = files[uri] ?: return null
        val pattern = Regex(Regex.escape(name) + "\\s*=\\s*new\\s+(\\w+)")
        return lines.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) }
    }

    /** The classes that implement or extend [name]. */
    fun implementationsOf(name: String): List<FakeDeclaration> {
        val pattern = Regex("""(class|enum)\s+\w+[^{]*\b(implements|extends)\b[^{]*\b${Regex.escape(name)}\b""")
        return declarations.filter { declaration ->
            val text = files[declaration.uri]?.getOrNull(declaration.line).orEmpty()
            declaration.kind == CLASS && pattern.containsMatchIn(text)
        }
    }

    /** The calls of [uri] to functions declared nowhere: `name(` not after `->`, `::`, `function` or `new`. */
    fun unknownCalls(uri: String): List<FakeOccurrence> {
        val lines = files[uri] ?: return emptyList()
        val known = declarations.map { it.name }.toSet() + BUILT_INS.keys + KEYWORDS
        return lines.flatMapIndexed { index, text ->
            CALL.findAll(text.substringBefore("//")).mapNotNull { match ->
                val name = match.groupValues[2]
                val prefix = match.groupValues[1]
                val isMember = prefix.endsWith("->") || prefix.endsWith("::") || prefix.endsWith("$")
                val isDeclarationOrNew = Regex("""(function|new|fn)\s+$""").containsMatchIn(text.substring(0, match.groups[2]!!.range.first))
                val unknown = name !in known && !isMember && !isDeclarationOrNew
                if (unknown) FakeOccurrence(uri, index, match.groups[2]!!.range.first, name.length) else null
            }.toList()
        }
    }

    private fun declarationsOf(uri: String, lines: List<String>): List<FakeDeclaration> {
        val found = ArrayList<FakeDeclaration>()
        var currentClass: FakeDeclaration? = null
        lines.forEachIndexed { index, text ->
            val endOfClass = currentClass?.endLine ?: Int.MAX_VALUE
            if (index > endOfClass) currentClass = null
            TYPE.find(text)?.let { match ->
                val kind =
                    when (match.groupValues[1]) {
                        "interface" -> INTERFACE
                        "enum" -> ENUM
                        else -> CLASS
                    }
                val nameGroup = match.groups[2]!!
                val declaration = declaration(uri, lines, index, nameGroup.value, kind, nameGroup.range.first, null)
                found += declaration
                currentClass = declaration
            }
            FUNCTION.find(text)?.let { match ->
                val nameGroup = match.groups[1]!!
                val parent = currentClass?.name
                val kind = if (parent != null) METHOD else FUNCTION_KIND
                found += declaration(uri, lines, index, nameGroup.value, kind, nameGroup.range.first, parent)
            }
        }
        return found
    }

    @Suppress("LongParameterList")
    private fun declaration(uri: String, lines: List<String>, index: Int, name: String, kind: Int, column: Int, parent: String?): FakeDeclaration {
        val text = lines[index]
        val start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val signature = text.trim().removeSuffix("{").trim()
        return FakeDeclaration(uri, name, kind, index, column, start, endOf(lines, index), signature, docblockAbove(lines, index), parent)
    }

    // The line of the brace closing the one opened on [index] or the next line, [index] for a declaration without body.
    private fun endOf(lines: List<String>, index: Int): Int {
        var depth = 0
        var opened = false
        for (line in index until lines.size) {
            for (c in lines[line]) {
                if (c == '{') {
                    depth++
                    opened = true
                } else if (c == '}') {
                    depth--
                }
            }
            if (opened && depth <= 0) return line
            if (!opened && line > index + 1) return index
        }
        return lines.lastIndex.coerceAtLeast(index)
    }

    // The text of the `/** ... */` block right above [index], without its stars; null when there is none.
    private fun docblockAbove(lines: List<String>, index: Int): String? {
        var end = index - 1
        while (end >= 0 && lines[end].isBlank()) end--
        if (end < 0 || !lines[end].trim().endsWith("*/")) return null
        var start = end
        while (start >= 0 && !lines[start].trim().startsWith("/**")) start--
        if (start < 0) return null
        val text =
            (start..end).map { lines[it].trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim() }
        return text.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        /** The PHP files under [rootUri], by URI, as an indexing server reads them. */
        fun readDisk(rootUri: String?): Map<String, List<String>> {
            val root = rootUri?.let { runCatching { File(URI(it)) }.getOrNull() } ?: return emptyMap()
            val files =
                root
                    .walkTopDown()
                    .onEnter { it.name != ".git" }
                    .filter { it.isFile && it.extension == "php" }
                    .take(MAX_DISK_FILES)
            // Named like the client names them: Path.toUri, as the IDE server does.
            // A file deleted while the directory is read is left out, as a real server would.
            val read = files.mapNotNull { file -> runCatching { file.toPath().toUri().toString() to readShared(file) }.getOrNull() }
            return read.toMap()
        }

        /** The lines of the file at [uri], null when it cannot be read (deleted meanwhile). */
        fun readFile(uri: String): List<String>? = runCatching { readShared(File(URI(uri))) }.getOrNull()

        // NIO opens files sharing their deletion on Windows (java.io does not): the IDE can still delete them meanwhile.
        private fun readShared(file: File): List<String> = java.nio.file.Files.readAllLines(file.toPath())

        const val CLASS = 5
        const val METHOD = 6
        const val ENUM = 10
        const val INTERFACE = 11
        const val FUNCTION_KIND = 12
        private const val MAX_DISK_FILES = 5_000
        private val TYPE = Regex("""^\s*(?:(?:abstract|final|readonly)\s+)*(class|interface|trait|enum)\s+(\w+)""")
        private val FUNCTION = Regex("""\bfunction\s+(\w+)\s*\(""")
        private val CALL = Regex("""(^|[^\w$>:\\]|->|::|\$)(\w+)\s*\(""")

        /** Built-in functions and their documentation. */
        val BUILT_INS =
            mapOf(
                "strlen" to ("function strlen(string \$string): int" to "Get string length"),
                "strtoupper" to ("function strtoupper(string \$string): string" to "Make a string uppercase"),
                "str_replace" to ("function str_replace(\$search, \$replace, \$subject): string" to "Replace all occurrences of the search string with the replacement string"),
                "array_map" to ("function array_map(?callable \$callback, array \$array): array" to "Applies the callback to the elements of the given arrays"),
            )

        private val KEYWORDS =
            setOf(
                "if", "elseif", "while", "for", "foreach", "switch", "match", "array", "isset", "unset", "empty", "list",
                "echo", "print", "return", "function", "fn", "catch", "declare", "exit", "die", "new", "eval", "include",
                "require", "include_once", "require_once", "and", "or",
            )

        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    }
}
