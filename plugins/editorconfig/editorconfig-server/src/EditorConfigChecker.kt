package org.example.kotlineditor.editorconfig.server

import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.slashed
import org.example.kotlineditor.server.TextFiles
import java.nio.file.Files
import java.nio.file.Path

/** One rule a file of the project breaks, as the tool window lists it. */
data class EditorConfigViolation(
    /** Path of the file, relative to the project root, with `/` separators. */
    val path: String,
    /** Line the violation is about, counted from 1; 0 when it is about the whole file. */
    val line: Int,
    /** The rule broken: `indent-style`, `indent-size`, `end-of-line` or `final-newline`. */
    val rule: String,
    val message: String,
)

/** Files larger than this are not read (1 MB). */
internal const val MAX_FILE_BYTES = 1024L * 1024

/** What a tab counts for when the width of an indent is checked. */
internal const val TAB_WIDTH = 4

private const val MAX_PER_FILE = 20
private const val INDENT_STYLE_RULE = "indent-style"
private const val INDENT_SIZE_RULE = "indent-size"
private const val END_OF_LINE_RULE = "end-of-line"
private const val FINAL_NEWLINE_RULE = "final-newline"

/**
 * Checks the files at [paths] (relative to [root], the directory holding `.editorconfig`) against it, in the order
 * given. A file that is binary, too large, missing or outside [root] is skipped: the checker reads only.
 */
internal fun checkFiles(root: Path, paths: List<String>): List<EditorConfigViolation> {
    val config = readConfig(root) ?: return emptyList()
    return paths.flatMap { relative -> checkFile(root, relative, config) }
}

private fun readConfig(root: Path): EditorConfigFile? {
    val config = TextFiles.read(root.resolve(CONFIG_NAME), MAX_FILE_BYTES)
    val text = config as? FileContent.Text ?: return null
    return parseEditorConfig(text.lines.joinToString(text.lineSeparator))
}

private fun checkFile(root: Path, relative: String, config: EditorConfigFile): List<EditorConfigViolation> {
    val path = resolve(root, relative) ?: return emptyList()
    val content = TextFiles.read(path, MAX_FILE_BYTES) as? FileContent.Text ?: return emptyList()
    val checks = config.checksFor(fileNameOf(relative))
    val violations = mutableListOf<EditorConfigViolation>()
    violations += fileViolations(relative, content, checks)
    for ((index, line) in content.lines.withIndex()) {
        if (violations.size >= MAX_PER_FILE) break
        val indentation = indentOf(line) ?: continue
        violations += indentationViolations(relative, index + 1, indentation, checks)
    }
    return violations
}

// The whole-file checks: the line endings and the final newline. An empty file has none.
private fun fileViolations(relative: String, content: FileContent.Text, checks: EditorConfigChecks): List<EditorConfigViolation> {
    if (content.lines.singleOrNull()?.isEmpty() == true) return emptyList()
    val violations = mutableListOf<EditorConfigViolation>()
    val expected = checks.endOfLine
    if (expected != null && content.lineSeparator != expected.separator) {
        val message = endOfLineMessage(expected, content.lineSeparator)
        violations += EditorConfigViolation(relative, 0, END_OF_LINE_RULE, message)
    }
    if (checks.insertFinalNewline == true && !endsWithNewline(content.lines)) {
        violations += EditorConfigViolation(relative, 0, FINAL_NEWLINE_RULE, "The file does not end with a newline")
    }
    return violations
}

// The checks of one line's indent: the style (a space where tabs are asked) and the width of the indent, which must
// be a multiple of indent_size when spaces are asked (a tab counts [TAB_WIDTH] wide). Blank lines are not checked.
private fun indentationViolations(
    relative: String,
    line: Int,
    indentation: String,
    checks: EditorConfigChecks,
): List<EditorConfigViolation> {
    val violations = mutableListOf<EditorConfigViolation>()
    if (checks.indentStyle == IndentStyle.TAB && indentation.contains(' ')) {
        violations +=
            EditorConfigViolation(relative, line, INDENT_STYLE_RULE, "The line is indented with a space, not with tabs")
    }
    val size = checks.indentSize
    if (checks.indentStyle == IndentStyle.SPACE && size != null) {
        val width = widthOf(indentation)
        if (width % size != 0) {
            val message = "The indent is $width wide, not a multiple of indent_size ($size)"
            violations += EditorConfigViolation(relative, line, INDENT_SIZE_RULE, message)
        }
    }
    return violations
}

// The leading whitespace of [line], or null when the line is blank or starts with something else.
private fun indentOf(line: String): String? {
    val indentation = line.takeWhile { it == ' ' || it == '\t' }
    val onlyWhitespace = indentation.length == line.length
    return if (onlyWhitespace) null else indentation.ifEmpty { null }
}

// The width [indentation] covers: a space is one column, a tab [TAB_WIDTH] of them.
private fun widthOf(indentation: String): Int =
    indentation.sumOf { char -> if (char == '\t') TAB_WIDTH else 1 }

// A file ends with a newline when `splitLines` left an empty line last ("abc\n" is ["abc", ""]).
private fun endsWithNewline(lines: List<String>): Boolean = lines.lastOrNull()?.isEmpty() == true

private fun endOfLineMessage(expected: EndOfLine, found: String): String {
    val foundName = if (found == EndOfLine.CRLF.separator) "CRLF" else "LF"
    val expectedName = if (expected == EndOfLine.CRLF) "CRLF" else "LF"
    return "The file uses $foundName line endings, .editorconfig asks for $expectedName"
}

// The file at [relative], or null when the path would leave [root] (paths from the client use `/` everywhere).
private fun resolve(root: Path, relative: String): Path? {
    val parts = slashed(relative).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    return parts.fold(root) { directory, name -> directory.resolve(name) }
}

private fun fileNameOf(relative: String): String = relative.substringAfterLast('/')

private const val CONFIG_NAME = ".editorconfig"
