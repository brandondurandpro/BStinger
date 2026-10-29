package org.example.kotlineditor.state

/**
 * Finds foldable regions: multi-line `{}`/`[]` blocks and block comments, start line -> end line.
 * Strings are skipped so that brackets inside them do not count.
 */
internal class FoldRegions {
    val regions = HashMap<Int, Int>()
    private val openBrackets = ArrayList<Int>()
    private var commentStart = -1

    fun scan(index: Int, line: String) {
        var i = 0
        while (i < line.length) i = if (commentStart >= 0) inComment(index, line, i) else inCode(index, line, i)
    }

    private fun inComment(index: Int, line: String, from: Int): Int {
        val end = line.indexOf("*/", from)
        if (end >= 0) {
            if (index > commentStart) regions.putIfAbsent(commentStart, index)
            commentStart = -1
        }
        return if (end < 0) line.length else end + 2
    }

    private fun inCode(index: Int, line: String, i: Int): Int {
        val c = line[i]
        return when {
            line.startsWith("//", i) -> {
                line.length
            }

            line.startsWith("/*", i) -> {
                commentStart = index
                i + 2
            }

            c == '"' || c == '\'' -> {
                stringEnd(line, i) + 1
            }

            c == '{' || c == '[' -> {
                openBrackets += index
                i + 1
            }

            c == '}' || c == ']' -> {
                close(index)
                i + 1
            }

            else -> {
                i + 1
            }
        }
    }

    private fun close(index: Int) {
        val start = openBrackets.removeLastOrNull() ?: return
        if (index > start) regions[start] = maxOf(regions[start] ?: 0, index)
    }

    private fun stringEnd(line: String, start: Int): Int {
        var j = start + 1
        while (j < line.length && line[j] != line[start]) j += if (line[j] == '\\') 2 else 1
        return j
    }
}
