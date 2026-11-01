package org.example.kotlineditor.server

import org.example.kotlineditor.model.BlameLine
import java.time.Instant
import java.time.ZoneOffset

private const val COMMIT_HASH_LENGTH = 40

/**
 * Parses `git blame --line-porcelain`: a header with the commit and final line number, then the author fields and
 * the line text after a tab. Each line is fully annotated (unlike plain `--porcelain`).
 */
internal fun parseBlame(output: String): List<BlameLine> {
    val lines = ArrayList<BlameLine>()
    var commit = ""
    var line = -1
    var author = ""
    var timestamp = 0L
    var offset = ZoneOffset.UTC
    output.lineSequence().forEach { raw ->
        if (raw.startsWith('\t')) {
            if (line >= 0) lines += BlameLine(line, commit, author, dateOf(timestamp, offset))
            line = -1
        } else if (raw.startsWith("author ")) {
            author = raw.removePrefix("author ")
        } else if (raw.startsWith("author-time ")) {
            timestamp = raw.removePrefix("author-time ").toLongOrNull() ?: 0L
        } else if (raw.startsWith("author-tz ")) {
            offset =
                runCatching { ZoneOffset.of(raw.removePrefix("author-tz ").trim()) }
                    .getOrDefault(ZoneOffset.UTC)
        } else {
            parseBlameHeader(raw)?.let { (hash, finalLine) ->
                commit = hash
                line = finalLine - 1
                author = ""
                timestamp = 0L
                offset = ZoneOffset.UTC
            }
        }
    }
    return lines
}

private fun parseBlameHeader(raw: String): Pair<String, Int>? {
    val parts = raw.split(' ')
    val hash = parts.firstOrNull()?.removePrefix("^")
    val fullHash = hash?.takeIf { it.length == COMMIT_HASH_LENGTH }
    val finalLine = parts.getOrNull(2)?.toIntOrNull()
    if (fullHash == null || finalLine == null) {
        return null
    }
    return fullHash.take(SHORT_HASH_LENGTH) to finalLine
}

private fun dateOf(timestamp: Long, offset: ZoneOffset): String =
    if (timestamp <= 0) {
        ""
    } else {
        Instant
            .ofEpochSecond(timestamp)
            .atOffset(offset)
            .toLocalDate()
            .toString()
    }
