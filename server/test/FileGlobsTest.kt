package org.example.kotlineditor.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileGlobsTest {
    private fun selects(patterns: String, path: String): Boolean {
        val globs = checkNotNull(FileGlobs.parse(patterns))
        return globs.selects(path.substringAfterLast('/'), path)
    }

    @Test
    fun `no pattern is no filter`() {
        assertNull(FileGlobs.parse(null))
        assertNull(FileGlobs.parse("  , ,"))
        assertNull(FileGlobs.parse("!"))
    }

    @Test
    fun `patterns are separated by commas, except inside braces`() {
        assertEquals(listOf("*.kt", "*.java"), FileGlobs.parse("*.kt, *.java")?.including)
        assertEquals(listOf("*.{kt,java}", "*.md"), FileGlobs.parse("*.{kt,java}, *.md")?.including)
        assertEquals(listOf("*.xml"), FileGlobs.parse("*.kt, !*.xml")?.excluding)
        assertEquals(listOf("*.kt", "!*.xml"), FileGlobs.parse("*.kt, !*.xml")?.all)
    }

    @Test
    fun `including patterns select the files of some extensions`() {
        assertTrue(selects("*.kt, *.java", "src/One.kt"))
        assertTrue(selects("*.kt, *.java", "src/Two.java"))
        assertFalse(selects("*.kt, *.java", "src/config.xml"))
    }

    @Test
    fun `with excluding patterns only, every other file is selected`() {
        assertTrue(selects("!*.xml", "src/One.kt"))
        assertFalse(selects("!*.xml", "src/deep/config.xml"))
    }

    @Test
    fun `an exclusion wins over an inclusion`() {
        assertFalse(selects("src/**, !*.xml", "src/config.xml"))
        assertTrue(selects("src/**, !*.xml", "src/One.kt"))
    }

    @Test
    fun `a directory is all its files, at any depth`() {
        assertFalse(selects("!build/", "build/out.txt"))
        assertFalse(selects("!build/", "module/build/out.txt"))
        assertTrue(selects("!build/", "src/One.kt"))
        assertFalse(selects("!src/test/", "src/test/Two.kt"))
        assertTrue(selects("!src/test/", "src/main/One.kt"))
    }

    @Test
    fun `an invalid pattern selects nothing and excludes nothing`() {
        assertFalse(selects("[", "src/One.kt"))
        assertTrue(selects("!{", "src/One.kt"))
    }
}
