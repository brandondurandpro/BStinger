package org.example.kotlineditor.scratchpad.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScratchpadFilesTest {
    private val root: Path = Files.createTempDirectory("scratchpad")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `loading a project without notes answers null and creates no file`() {
        assertNull(loadScratchpad(root))
        assertFalse(Files.exists(root.resolve("scratchpad.md")))
    }

    @Test
    fun `loading reads the notes as they are on the disk`() {
        root.resolve("scratchpad.md").writeBytes("Des notes\navec accent\n".toByteArray(StandardCharsets.UTF_8))

        assertEquals("Des notes\navec accent\n", loadScratchpad(root))
    }

    @Test
    fun `saving writes plain UTF-8 with a final newline`() {
        saveScratchpad(root, "Remember the milk")

        val bytes = root.resolve("scratchpad.md").readBytes()
        assertEquals("Remember the milk\n", String(bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `an empty text saves an empty file`() {
        saveScratchpad(root, "")

        assertEquals(0, root.resolve("scratchpad.md").readBytes().size)
        assertEquals("", loadScratchpad(root))
    }

    @Test
    fun `saving twice the same text writes the same bytes twice`() {
        saveScratchpad(root, "Remember the milk")
        val afterFirst = root.resolve("scratchpad.md").readBytes()

        saveScratchpad(root, "Remember the milk")
        val afterSecond = root.resolve("scratchpad.md").readBytes()

        assertTrue(afterFirst.contentEquals(afterSecond))
    }

    @Test
    fun `a text that already ends with a newline is written as given`() {
        saveScratchpad(root, "one\ntwo\n")

        assertEquals("one\ntwo\n", loadScratchpad(root))
    }

    @Test
    fun `saving keeps the charset of the notes that are not UTF-8`() {
        // A Latin-1 file (an é is one byte): saving it again must keep every byte.
        val latin1 = "Des notes accentuées\n".toByteArray(StandardCharsets.ISO_8859_1)
        root.resolve("scratchpad.md").writeBytes(latin1)

        val loaded = loadScratchpad(root)
        saveScratchpad(root, loaded.orEmpty())

        assertTrue(root.resolve("scratchpad.md").readBytes().contentEquals(latin1))
    }

    @Test
    fun `saving creates the file when the project holds none`() {
        assertFalse(Files.exists(root.resolve("scratchpad.md")))

        saveScratchpad(root, "The first note")

        assertEquals("The first note\n", loadScratchpad(root))
    }
}
