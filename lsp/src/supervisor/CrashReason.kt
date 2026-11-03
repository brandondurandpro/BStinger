package org.example.kotlineditor.lsp.supervisor

/**
 * The line of a crashed server's stderr that tells the user why, rather than the last one: phpactor, like any
 * Symfony Console program, ends with the usage of its command (`language-server [--address ADDRESS] [--no-loop]`)
 * after the error block that explains the crash.
 */
internal object CrashReason {
    // `In ArgumentAssert.php line 52:`, the header of a Symfony Console error block.
    private val SYMFONY_HEADER = Regex("""^In \S+ line \d+:\s*$""")
    private val ERROR_WORDS = listOf("fatal error", "error:", "exception")
    private const val INDENT = ' '

    /** What explains the crash in [stderr] (the lines of the process, oldest first), null when it wrote nothing. */
    fun explain(stderr: List<String>): String? = symfonyBlock(stderr) ?: lastError(stderr) ?: lastLine(stderr)

    // The indented lines after the last header, padding removed; the block wraps long messages over several lines.
    private fun symfonyBlock(stderr: List<String>): String? {
        val header = stderr.indexOfLast { SYMFONY_HEADER.matches(it) }
        if (header < 0) return null
        val block = stderr.drop(header + 1).takeWhile { it.isBlank() || it.startsWith(INDENT) }
        val words = block.map { it.trim() }.filter { it.isNotEmpty() }
        return words.joinToString(" ").ifEmpty { null }
    }

    private fun lastError(stderr: List<String>): String? {
        val found =
            stderr.lastOrNull { line ->
                val lower = line.lowercase()
                ERROR_WORDS.any { lower.contains(it) }
            }
        return found?.trim()
    }

    private fun lastLine(stderr: List<String>): String? = stderr.lastOrNull { it.isNotBlank() }?.trim()
}
