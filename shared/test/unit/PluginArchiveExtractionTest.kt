package org.example.kotlineditor.unit

import org.example.kotlineditor.state.extract
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A plugin archive never writes outside the folder of its plugin ("Zip Slip"). */
class PluginArchiveExtractionTest {
    private val base = Files.createTempDirectory("plugin-extract").toFile()
    private val folder = File(base, "plugins/p").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun archive(vararg entries: String): File {
        val file = File(base, "p.plugin")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("content of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `the files at the root are extracted`() {
        extract(archive("plugin.yaml", "plugin.jar"), folder)
        assertEquals(setOf("plugin.yaml", "plugin.jar"), folder.list().orEmpty().toSet())
    }

    @Test
    fun `an entry that climbs out of the folder is not written`() {
        extract(archive("../evil.txt", "..\\evil2.txt", "plugin.yaml"), folder)
        assertFalse(File(base, "plugins/evil.txt").exists())
        assertFalse(File(base, "plugins/evil2.txt").exists())
        assertEquals(listOf("plugin.yaml"), folder.list().orEmpty().toList())
    }

    @Test
    fun `an absolute path or a nested entry is not written`() {
        extract(archive("/tmp/absolute-evil.txt", "sub/inner.txt", "plugin.yaml"), folder)
        assertFalse(File("/tmp/absolute-evil.txt").exists())
        assertTrue(File(folder, "sub").list().isNullOrEmpty())
        assertEquals(listOf("plugin.yaml"), folder.list().orEmpty().toList())
    }
}
