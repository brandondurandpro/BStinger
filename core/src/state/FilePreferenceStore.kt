package org.example.kotlineditor.state

import java.io.File
import java.io.IOException
import java.util.Properties

/** Preferences in a `.properties` [file]; a missing or corrupt one reads as empty. */
class FilePreferenceStore(private val file: File = defaultFile()) : PreferenceStore {
    override fun load(): Map<String, String> =
        try {
            val properties = Properties()
            file.takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(properties::load)
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        } catch (_: IOException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            // Properties.load rejects malformed \u escapes.
            emptyMap()
        }

    /** Writes next to the file then renames, so a crash never leaves a half-written file. Failures are ignored. */
    override fun save(values: Map<String, String>) {
        try {
            val properties = Properties()
            values.forEach { (key, value) -> properties.setProperty(key, value) }
            file.absoluteFile.parentFile?.mkdirs()
            val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
            temp.writer(Charsets.UTF_8).use { properties.store(it, null) }
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            // Preferences are a convenience: losing them must never break the IDE.
        }
    }

    companion object {
        /** `~/.kotlin-editor/preferences.properties`, on the machine running the IDE (not the server's). */
        fun defaultFile(): File {
            val directory = File(System.getProperty("user.home"), ".kotlin-editor")
            return File(directory, "preferences.properties")
        }
    }
}
