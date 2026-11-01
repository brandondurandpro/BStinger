package org.example.kotlineditor.lsp.provision

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A minimal reader of the tar archives npm publishes (the gzip layer is the caller's): ustar entries, regular files
 * and directories, with the long names of the ustar prefix field, of PAX `path` records and of GNU `L` entries.
 * Links, devices and other special entries are rejected rather than extracted approximately, and no entry may
 * place a file outside the extraction directory.
 */
internal object TarArchive {
    private const val BLOCK = 512
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val OCTAL = 8
    private const val MAX_ENTRY_SIZE = 256L * 1024 * 1024
    private const val MAX_HEADER_RECORD = 1024 * 1024

    private const val REGULAR = '0'.code.toByte()
    private const val REGULAR_OLD: Byte = 0
    private const val DIRECTORY = '5'.code.toByte()
    private const val PAX_ENTRY = 'x'.code.toByte()
    private const val PAX_GLOBAL = 'g'.code.toByte()
    private const val GNU_LONG_NAME = 'L'.code.toByte()

    /** Expands every entry of [tar] (the uncompressed tar stream) into [into], which is created if needed. */
    fun extract(tar: InputStream, into: Path) {
        val root = into.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val header = ByteArray(BLOCK)
        var longName: String? = null
        while (tar.readNBytes(header, 0, BLOCK) == BLOCK && header.any { it != 0.toByte() }) {
            val size = entrySize(header)
            val name = longName ?: headerName(header)
            longName = null
            when (header[TYPE_OFFSET]) {
                REGULAR, REGULAR_OLD -> copyEntry(tar, size, target(root, name))
                DIRECTORY -> Files.createDirectories(target(root, name)).also { skipPadded(tar, size) }
                PAX_ENTRY -> longName = paxPath(readRecord(tar, size))
                GNU_LONG_NAME -> longName = String(readRecord(tar, size), Charsets.UTF_8).trimEnd('\u0000')
                PAX_GLOBAL -> skipPadded(tar, size)
                else -> throw IOException("Unsupported tar entry type '${header[TYPE_OFFSET].toInt().toChar()}': $name")
            }
        }
    }

    private fun headerName(header: ByteArray): String {
        val name = text(header, 0, NAME_LENGTH)
        val ustar = text(header, MAGIC_OFFSET, "ustar".length) == "ustar"
        val prefix = if (ustar) text(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun entrySize(header: ByteArray): Long {
        val size = text(header, SIZE_OFFSET, SIZE_LENGTH).trim().toLongOrNull(OCTAL)
        if (size == null || size !in 0..MAX_ENTRY_SIZE) throw IOException("Invalid tar entry size")
        return size
    }

    // The path an entry extracts to; an absolute name or `..` that leaves the extraction directory is refused.
    private fun target(root: Path, name: String): Path {
        val path = root.resolve(name.trimStart('/')).normalize()
        if (!path.startsWith(root) || path == root) throw IOException("Tar entry outside the target directory: $name")
        return path
    }

    private fun copyEntry(tar: InputStream, size: Long, target: Path) {
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { output ->
            var remaining = size
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Truncated tar entry ${target.fileName}")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        tar.skipNBytes(padding(size))
    }

    private fun readRecord(tar: InputStream, size: Long): ByteArray {
        if (size > MAX_HEADER_RECORD) throw IOException("Tar header record too large")
        val record = tar.readNBytes(size.toInt())
        if (record.size.toLong() != size) throw IOException("Truncated tar header record")
        tar.skipNBytes(padding(size))
        return record
    }

    // PAX records are `<length> <key>=<value>\n`; only `path` matters here.
    private fun paxPath(record: ByteArray): String? =
        String(record, Charsets.UTF_8)
            .lineSequence()
            .map { it.substringAfter(' ', "") }
            .firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")

    private fun skipPadded(tar: InputStream, size: Long) = tar.skipNBytes(size + padding(size))

    private fun padding(size: Long) = (BLOCK - size % BLOCK) % BLOCK

    private fun text(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: offset + length
        return String(header, offset, end - offset, Charsets.UTF_8)
    }
}
