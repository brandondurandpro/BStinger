package org.example.kotlineditor.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Path

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// One lock per file being written, removed once nobody holds or waits for it.
private class PathLock {
    val mutex = Mutex()
    var users = 0
}

private val pathLocks = HashMap<String, PathLock>()

// How many (writer, file) pairs [latestWrites] remembers: far more than the files a client writes at the same time.
private const val REMEMBERED_WRITES = 4096
private const val LOAD_FACTOR = 0.75f

// The order of the latest write of each file by each writer, kept after its lock is gone: a write sent before it
// that reaches the file only now is older.
private val latestWrites =
    object : LinkedHashMap<String, Long>(REMEMBERED_WRITES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > REMEMBERED_WRITES
    }

/**
 * Runs [block], which writes [path], once the writes of the same file running or waiting are done: the bytes of one
 * write never mix with another's. The same file is the same lock whatever path names it (through a symbolic link,
 * in another case on Windows).
 */
internal suspend fun <T> withPathLock(path: Path, block: suspend () -> T): T = withLockOf(lockKeyOf(path), block)

/**
 * Runs [write], which writes [path] for [writer], under the lock of [withPathLock], unless [writer] already wrote
 * [path] with a request it sent after this one ([order], its request ids, which increase): two saves sent in a row
 * then leave the second one's text, even when the first one reaches the lock last (it is larger, its thread was
 * slower to start). Returns what [write] returned, `null` when it was dropped (it did not run, and so checked
 * against the disk neither).
 */
internal suspend fun <T> writeInOrder(path: Path, writer: String, order: Long, write: suspend () -> T): T? {
    val key = lockKeyOf(path)
    val writerKey = "$writer\u0000$key"
    return withLockOf(key) {
        val latest = synchronized(latestWrites) { latestWrites[writerKey] }
        val newerWritten = latest != null && latest > order
        if (!newerWritten) {
            val written = write()
            synchronized(latestWrites) { latestWrites[writerKey] = order }
            written
        } else {
            null
        }
    }
}

private suspend fun <T> withLockOf(key: String, block: suspend () -> T): T {
    val lock = synchronized(pathLocks) { pathLocks.getOrPut(key, ::PathLock).also { it.users++ } }
    try {
        return lock.mutex.withLock { block() }
    } finally {
        synchronized(pathLocks) {
            lock.users--
            if (lock.users == 0) pathLocks.remove(key)
        }
    }
}

// The real path of the file, or for a file not created yet, the real path of its directory and its name.
private fun lockKeyOf(path: Path): String {
    val absolute = path.toAbsolutePath().normalize()
    val real = realPathOf(absolute)
    val parent = absolute.parent
    val realParent = if (real == null && parent != null) realPathOf(parent) else null
    val fileName = absolute.fileName
    val resolved =
        when {
            real != null -> real
            realParent != null && fileName != null -> realParent.resolve(fileName)
            else -> absolute
        }
    val key = resolved.toString()
    return if (WINDOWS) key.lowercase() else key
}

private fun realPathOf(path: Path): Path? =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }
