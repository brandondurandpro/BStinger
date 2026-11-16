package org.example.kotlineditor.editorconfig.server

/** How `.editorconfig` asks the lines of a file to be indented. */
enum class IndentStyle {
    SPACE,
    TAB,
}

/** How `.editorconfig` asks the lines of a file to be ended. */
enum class EndOfLine(val separator: String) {
    LF("\n"),
    CRLF("\r\n"),
}

/** The checks `.editorconfig` asks for one file, as the checker runs them (a null rule is not checked). */
data class EditorConfigChecks(
    val indentStyle: IndentStyle?,
    val indentSize: Int?,
    val endOfLine: EndOfLine?,
    val insertFinalNewline: Boolean?,
)

// The keys the checker reads; the others (charset...) are ignored.
private const val INDENT_STYLE = "indent_style"
private const val INDENT_SIZE = "indent_size"
private const val END_OF_LINE = "end_of_line"
private const val INSERT_FINAL_NEWLINE = "insert_final_newline"
private const val ROOT = "root"

/** One section of `.editorconfig`: a glob (`*.kt`) and the keys it sets. */
internal data class Section(val glob: String, val keys: Map<String, String>)

/**
 * The parsed `.editorconfig` of a project root. [checksFor] gathers the keys of every section whose glob matches
 * [fileName] (later sections win over earlier ones), and turns them into the checks the checker runs. A key before
 * any section is not a rule for the files: it is the `root` marker.
 */
internal class EditorConfigFile(
    private val isRoot: Boolean,
    private val sections: List<Section>,
) {
    fun checksFor(fileName: String): EditorConfigChecks {
        val keys = LinkedHashMap<String, String>()
        for (section in sections) {
            if (matches(section.glob, fileName)) keys.putAll(section.keys)
        }
        return EditorConfigChecks(
            indentStyle = keys.indentStyle(),
            indentSize = keys.indentSize(),
            endOfLine = keys.endOfLine(),
            insertFinalNewline = keys.finalNewline(),
        )
    }
}

internal fun parseEditorConfig(text: String): EditorConfigFile {
    val parser = Parser()
    for (rawLine in text.split('\n')) {
        parser.read(rawLine.trim())
    }
    return parser.file()
}

// One line of the file at a time: a section starts with `[glob]`, a key is `key = value`, comments start with `#`
// or `;`, and `root = true` before any section marks the top of the tree (the checker reads only this file).
private class Parser {
    private val sections = mutableListOf<Section>()
    private var current: MutableMap<String, String>? = null
    private var glob: String? = null
    private var isRoot = false

    fun read(line: String) {
        when {
            line.isEmpty() || line.startsWith('#') || line.startsWith(';') -> return
            line.startsWith('[') -> startSection(line)
            else -> readKey(line)
        }
    }

    // `[glob]` starts a section; a header that misses its `]` still starts one (its glob names nothing).
    private fun startSection(line: String) {
        val end = line.indexOf(']')
        val parsed = line.substring(1, if (end > 0) end else line.length).trim()
        val keys = mutableMapOf<String, String>()
        glob = parsed
        current = keys
        sections += Section(parsed, keys)
    }

    private fun readKey(line: String) {
        val separator = line.indexOf('=')
        if (separator <= 0) return
        val key = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        val keys = current
        if (keys != null) {
            keys[key] = value
        } else if (key == ROOT && value == "true") {
            isRoot = true
        }
    }

    internal fun file(): EditorConfigFile {
        val parsed = sections
        return EditorConfigFile(isRoot, parsed)
    }
}

// A glob is the simple kind `.editorconfig` mostly uses: `*` matches any run of characters, `?` one character.
private fun matches(glob: String, fileName: String): Boolean {
    val pattern = buildString {
        for (char in glob) {
            when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(char.toString()))
            }
        }
    }
    return Regex(pattern).matches(fileName)
}

private fun Map<String, String>.indentStyle(): IndentStyle? =
    when (this[INDENT_STYLE]) {
        "space" -> IndentStyle.SPACE
        "tab" -> IndentStyle.TAB
        else -> null
    }

// `indent_size = tab` means a tab-wide indent: the checker has no width for it, so it is left unchecked.
private fun Map<String, String>.indentSize(): Int? = this[INDENT_SIZE]?.toIntOrNull()

private fun Map<String, String>.endOfLine(): EndOfLine? =
    when (this[END_OF_LINE]) {
        "lf" -> EndOfLine.LF
        "crlf" -> EndOfLine.CRLF
        else -> null
    }

private fun Map<String, String>.finalNewline(): Boolean? =
    when (this[INSERT_FINAL_NEWLINE]) {
        "true" -> true
        "false" -> false
        else -> null
    }
