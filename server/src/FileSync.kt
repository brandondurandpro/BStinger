package org.example.kotlineditor.server

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * How a write makes what it wrote durable: the file is opened with [writeOptions], [file] flushes it to the disk once
 * written, [directory] the entries of a directory (a file moved or created in it). A seam for the tests, which check
 * the order of the steps.
 */
internal interface DiskSync {
    val writeOptions: Set<OpenOption>

    fun file(channel: FileChannel)

    fun directory(path: Path)
}

/**
 * [DiskSync] on the disk: `fsync` of the file, and of its directory where the system allows it (not Windows).
 *
 * On Windows the file is written through to the disk instead (`FILE_FLAG_WRITE_THROUGH`, what [StandardOpenOption.DSYNC]
 * opens it with), and not flushed: `FlushFileBuffers` flushes the whole cache of the disk, everything any program left
 * there, and a rename rewriting 500 files took seconds more on the CI. NTFS journals the metadata in order, so the
 * move that follows is never on the disk before the bytes written through.
 */
internal object DurableSync : DiskSync {
    override val writeOptions: Set<OpenOption> = if (WINDOWS) setOf(StandardOpenOption.DSYNC) else emptySet()

    override fun file(channel: FileChannel) {
        if (!WINDOWS) channel.force(true)
    }

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
    val options =
        setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING) +
            sync.writeOptions
    FileChannel.open(target, options).use { channel ->
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
