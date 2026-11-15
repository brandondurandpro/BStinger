package org.example.kotlineditor.state

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipFile

/**
 * Expands a zip the way the AOT cache of the IDE needs it: every file and directory gets back the modification
 * time of its entry (the cache is only valid while the jars keep their dates), and no entry may land outside the
 * target directory (zip slip).
 */
object ZipExtraction {
    /**
     * Expands [zip] into [into], created if needed, without the first [stripComponents] segments of each path (the
     * top directory of the archive); an entry with fewer segments is skipped.
     */
    fun extract(zip: Path, into: Path, stripComponents: Int = 0) {
        val root = into.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val directories = ArrayList<Pair<Path, FileTime?>>()
        ZipFile(zip.toFile()).use { archive ->
            for (entry in archive.entries()) {
                val stripped = stripped(entry.name, stripComponents) ?: continue
                val target = targetOf(root, stripped, entry.name)
                val modified = entry.lastModifiedTime
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    directories.add(target to modified)
                } else {
                    Files.createDirectories(target.parent)
                    archive.getInputStream(entry).use { Files.copy(it, target) }
                    if (modified != null) Files.setLastModifiedTime(target, modified)
                }
            }
        }
        // Deepest first: filling a directory changes its date.
        for ((directory, modified) in directories.sortedByDescending { it.first.nameCount }) {
            if (modified != null) Files.setLastModifiedTime(directory, modified)
        }
    }

    private fun stripped(name: String, components: Int): String? {
        val segments = name.split('/').filter { it.isNotEmpty() }
        if (segments.size <= components) return null
        return segments.drop(components).joinToString("/")
    }

    // An absolute name or a `..` is refused, not extracted somewhere else (also when stripping would hide it).
    private fun targetOf(root: Path, stripped: String, original: String): Path {
        val escapes = isAbsoluteOrParent(original)
        val path = root.resolve(stripped).normalize()
        val outside = !path.startsWith(root) || path == root
        if (escapes || outside) {
            throw IOException("Zip entry outside the target directory: $original")
        }
        return path
    }

    private fun isAbsoluteOrParent(name: String): Boolean {
        val rooted = name.startsWith("/") || name.contains('\\') || name.contains(':')
        return rooted || name.split('/').contains("..")
    }
}
