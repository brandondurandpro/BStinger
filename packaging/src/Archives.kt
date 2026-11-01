package org.example.packaging

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/** Writes [dir] to the `.tar.gz` [output], with its dates and permissions (Linux and macOS). */
internal fun tarGz(dir: Path, output: Path) =
    run(listOf("tar", "-C", dir.parent.toString(), "-czf", output.toString(), dir.name))

/** Writes [dir] to the `.zip` [output], with its dates (Windows has no permissions to keep). */
internal fun zip(dir: Path, output: Path) {
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        Files.walk(dir).use { paths ->
            paths.sorted().forEach { path ->
                val entryName = path.relativeTo(dir.parent).joinToString("/") + if (path.isDirectory()) "/" else ""
                zip.putNextEntry(ZipEntry(entryName).apply { lastModifiedTime = path.getLastModifiedTime() })
                if (path.isRegularFile()) Files.copy(path, zip)
                zip.closeEntry()
            }
        }
    }
}
