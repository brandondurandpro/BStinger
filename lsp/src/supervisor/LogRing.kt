package org.example.kotlineditor.lsp.supervisor

import java.io.ByteArrayOutputStream

/** Splits a byte stream into the lines of [log], decoding UTF-8 per line; a line without end cannot grow. */
internal class LogLines(private val log: LogRing) {
    private val line = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, count: Int) {
        for (index in 0 until count) {
            val byte = bytes[index]
            when {
                byte == NEWLINE -> flush()
                line.size() < MAX_BYTES -> line.write(byte.toInt())
            }
        }
    }

    fun flush() {
        if (line.size() > 0) log.add(line.toString(Charsets.UTF_8).trimEnd('\r'))
        line.reset()
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()

        // Up to four bytes per character: enough for the longest line LogRing keeps.
        const val MAX_BYTES = (LogRing.MAX_LINE + 1) * 4
    }
}

/** The last [capacity] lines of a language server's log (its stderr and `window/logMessage`). Thread-safe. */
class LogRing(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<String>()

    /** Appends [line], cut to [MAX_LINE] characters so that one huge line cannot fill the memory. */
    fun add(line: String) {
        val kept = if (line.length > MAX_LINE) line.take(MAX_LINE) + "…" else line
        synchronized(lines) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(kept)
        }
    }

    /** The lines, oldest first. */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    /** The last line, if any. */
    fun last(): String? = synchronized(lines) { lines.lastOrNull() }

    /** Sizes. */
    companion object {
        /** Lines kept per server. */
        const val DEFAULT_CAPACITY = 2_000

        /** Longest line kept. */
        const val MAX_LINE = 2_000
    }
}
