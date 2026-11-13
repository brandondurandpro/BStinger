package org.example.kotlineditor.server

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

private const val EXCLUDING = '!'
private const val SEPARATOR = ','

/**
 * The file filter of a Find in Files: patterns separated by commas, the ones starting with `!` excluding
 * (`*.kt, *.java, !*.xml`, `!build/`). A file is searched when it matches one of the including patterns
 * (any file when there are none) and none of the excluding ones. A pattern without `/` applies to the name of the
 * file, a pattern with one to its path from the root of the project; a directory (`build/`, `!node_modules/`) is all
 * its files.
 */
internal class FileGlobs private constructor(val including: List<String>, val excluding: List<String>) {
    /** Every pattern, in the order written, the excluding ones with their `!`: what ripgrep takes with `--glob`. */
    val all: List<String> get() = including + excluding.map { "$EXCLUDING$it" }

    private val includingMatchers = including.map(::matcherOf)
    private val excludingMatchers = excluding.map(::matcherOf)

    /** Whether the file named [name], at [relativePath] from the root, is selected. */
    fun selects(name: String, relativePath: String): Boolean {
        val included = includingMatchers.isEmpty() || includingMatchers.any { it.matches(name, relativePath) }
        return included && excludingMatchers.none { it.matches(name, relativePath) }
    }

    private class Matcher(val globs: List<PathMatcher>, val byPath: Boolean) {
        // An invalid pattern selects no file (and excludes none).
        fun matches(name: String, relativePath: String): Boolean {
            val subject = runCatching { Paths.get(if (byPath) relativePath else name) }.getOrNull() ?: return false
            return globs.any { it.matches(subject) }
        }
    }

    private fun matcherOf(pattern: String): Matcher {
        val wholes = if (pattern.endsWith('/')) directoryPatterns(pattern) else listOf(pattern)
        val globs = wholes.mapNotNull(::compile)
        return Matcher(globs, byPath = wholes.any { it.contains('/') })
    }

    private fun compile(glob: String): PathMatcher? =
        runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull()

    // A directory is every file under it: `build/` under any `build` (the one of the root too), `src/build/` under
    // the one of `src` only.
    private fun directoryPatterns(pattern: String): List<String> {
        val directory = pattern.trimEnd('/').removePrefix("/")
        return if (directory.contains('/')) listOf("$directory/**") else listOf("$directory/**", "**/$directory/**")
    }

    companion object {
        /** The filter written as [text]; null when it has no pattern at all. */
        fun parse(text: String?): FileGlobs? {
            val patterns = splitPatterns(text.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }
            val excluding = patterns.filter { it.startsWith(EXCLUDING) }.map { it.removePrefix("$EXCLUDING").trim() }
            val including = patterns.filterNot { it.startsWith(EXCLUDING) }
            val usable = excluding.filter { it.isNotEmpty() }
            return if (including.isEmpty() && usable.isEmpty()) null else FileGlobs(including, usable)
        }

        // Commas separate patterns, except inside braces (`*.{kt,java}`).
        private fun splitPatterns(text: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            for (character in text) {
                when {
                    character == '{' -> depth++
                    character == '}' && depth > 0 -> depth--
                }
                if (character == SEPARATOR && depth == 0) {
                    parts += current.toString()
                    current.clear()
                } else {
                    current.append(character)
                }
            }
            parts += current.toString()
            return parts
        }
    }
}
