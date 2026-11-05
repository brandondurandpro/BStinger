package org.example.kotlineditor.server

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * How a write makes what it wrote durable: [file] flushes an open file to the disk, [directory] the entries of a
 * directory (a file moved or created in it). A seam for the tests, which check the order of the steps.
 */
internal interface DiskSync {
    fun file(channel: FileChannel)

    fun directory(path: Path)
}

/** [DiskSync] on the disk: `fsync` of the file, and of its directory where the system allows it (not Windows). */
internal object DurableSync : DiskSync {
    override fun file(channel: FileChannel) = channel.force(true)

    // Best effort: Windows cannot open a directory as a file, and some file systems refuse to sync one.
    override fun directory(path: Path) {
        if (WINDOWS) return
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The move is done; only its durability across a power loss is not guaranteed there.
        }
    }
}

// The file itself is written: what makes it what it is stays, but a crash in the middle leaves it half written.
internal fun writeInPlace(target: Path, bytes: ByteArray, created: Boolean, sync: DiskSync) {
    val options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    FileChannel.open(target, *options).use { channel ->
        writeAll(channel, bytes)
        sync.file(channel)
    }
    val parent = target.toAbsolutePath().parent
    if (created && parent != null) sync.directory(parent)
}

internal fun writeAll(channel: FileChannel, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) channel.write(buffer)
}
