package org.example.kotlineditor.server

import org.example.kotlineditor.model.BlameLine
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

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

private const val HASH_BUFFER_BYTES = 64 * 1024

/**
 * The blames of the files last asked, the oldest forgotten first. A blame stands for the content of the file (a
 * digest of its bytes: an edit that keeps the size and the modification time, as `rsync -t` or a coarse clock do,
 * still changes it) and the commit of HEAD; whether the file is still tracked is asked again only after the index
 * changed. A hit thus starts no process: the editor asks again every time its window regains focus.
 */
internal class BlameCache(private val capacity: Int) {
    /** What a blame depends on, besides the index: the commit of HEAD and the digest of the content. */
    data class Version(val head: String?, val content: String)

    private class Entry(val version: Version, val index: IndexStamp?, val lines: List<BlameLine>)

    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
        }

    /** How many blames are kept. */
    val size: Int get() = synchronized(entries) { entries.size }

    /**
     * The blame of [key] at [version], or `null` when it must be computed again. When the index changed, or its
     * stamp is unknown ([index] null), [stillTracked] tells whether the file is still tracked: if so the blame
     * stands, else it is dropped.
     */
    fun get(key: String, version: Version, index: IndexStamp?, stillTracked: () -> Boolean): List<BlameLine>? {
        val entry = synchronized(entries) { entries[key] }
        val current = entry?.takeIf { it.version == version } ?: return null
        val indexChanged = index == null || current.index != index
        val tracked = !indexChanged || stillTracked()
        if (indexChanged) {
            synchronized(entries) {
                if (tracked) entries[key] = Entry(version, index, current.lines) else entries.remove(key)
            }
        }
        return current.lines.takeIf { tracked }
    }

    fun put(key: String, version: Version, index: IndexStamp?, lines: List<BlameLine>) {
        synchronized(entries) { entries[key] = Entry(version, index, lines) }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/** The size and modification time of the index of a working tree: it changes when a file is added or removed. */
internal data class IndexStamp(val modified: Long, val size: Long) {
    companion object {
        /**
         * The stamp of the index of the working tree holding [root] (which may be one of its subdirectories), or
         * `null` when it cannot be read: unknown, so whether a file is tracked is then asked to Git every time.
         */
        fun read(root: String): IndexStamp? {
            val directory = workingTreeGitDirectory(root)
            val index = directory?.let { File(it, "index") }?.takeIf { it.isFile }
            return index?.let { IndexStamp(it.lastModified(), it.length()) }
        }
    }
}

/** A digest of the bytes of [file], or `null` when it cannot be read. */
internal fun contentDigest(file: File): String? =
    runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        Base64.getEncoder().encodeToString(digest.digest())
    }.getOrNull()

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
