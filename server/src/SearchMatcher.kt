package org.example.kotlineditor.server

import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.Structure

// How Find in Files matches a line: literally, or by regular expression, with the word boundaries of the
// `wholeWord` option.

/** How a line is matched: the zero-based column of its first match, or a negative one for no match. */
internal sealed interface Matcher {
    fun columnOf(line: String): Int
}

/** The matcher of the query of [options]: literal text, a regular expression, or nothing for an empty query. */
internal fun matcherOf(options: FindOptions): Matcher =
    when {
        options.text.isEmpty() -> EmptyMatcher
        !options.regex -> LiteralMatcher(options.text, options.ignoreCase, options.wholeWord)
        else -> RegexMatcher(regexOf(options))
    }

// A whole-word regex is bounded by word boundaries like ripgrep's `--word-regexp` for any pattern.
private fun regexOf(options: FindOptions): Regex {
    val flags = if (options.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val pattern = if (options.wholeWord) "\\b(?:${options.text})\\b" else options.text
    return Regex(pattern, flags)
}

/** A matcher of a query matching literally: the first occurrence bounded by word ends for a whole-word query. */
internal class LiteralMatcher(private val text: String, private val ignoreCase: Boolean, private val whole: Boolean) :
    Matcher {
    override fun columnOf(line: String): Int {
        if (!whole) return line.indexOf(text, ignoreCase = ignoreCase)
        return wholeWordColumnOf(line)
    }

    // The first occurrence of [text] bounded by non-word characters on both sides (letters, digits and `_`).
    private fun wholeWordColumnOf(line: String): Int {
        var start = line.indexOf(text, ignoreCase = ignoreCase)
        while (start >= 0) {
            if (bounded(line, start, start + text.length)) break
            start = line.indexOf(text, startIndex = start + 1, ignoreCase = ignoreCase)
        }
        return start
    }

    private fun bounded(line: String, from: Int, till: Int): Boolean {
        val before = line.getOrNull(from - 1)?.let(::isWordChar) ?: false
        val after = line.getOrNull(till)?.let(::isWordChar) ?: false
        return !before && !after
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}

/** A matcher of a query matching as a regular expression: the first occurrence of what it matches. */
internal class RegexMatcher(private val regex: Regex) : Matcher {
    override fun columnOf(line: String): Int = regex.find(line)?.range?.first ?: -1
}

/** A matcher of an empty query: nothing matches, and the batches are never asked for it. */
internal object EmptyMatcher : Matcher {
    override fun columnOf(line: String): Int = -1
}

/** The matches of [matcher] in the lines of [entry]: one per matched line, previewed to a sane length. */
internal fun matchesIn(files: LocalFileSystem, entry: FileEntry, matcher: Matcher): List<TextMatch> {
    val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
    return content.lines.withIndex().mapNotNull { (index, line) ->
        val column = matcher.columnOf(line)
        if (column < 0) null else TextMatch(entry, index, column, line.take(MAX_LINE_PREVIEW))
    }
}

/** The symbols [entry] outlines by hand, for the languages the local outline reaches (no language server). */
internal fun symbolsIn(files: LocalFileSystem, entry: FileEntry): List<FileSymbol> {
    val content = files.readNow(entry) as? FileContent.Text ?: return emptyList()
    val language = Language.fromFileName(pathName(entry.path))
    val declarations = Structure.outline(content.lines, language, Structure.blockCommentStates(content.lines))
    return declarations.map { FileSymbol(entry, it) }
}

// How much of a matched line travels to the client with its match, like ripgrep shows (see [matchesIn]).
private const val MAX_LINE_PREVIEW = 500
