package org.example.kotlineditor.harness

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillDiscoveryTest {
    private val root = Files.createTempDirectory("skills-project").toFile()
    private val data = Files.createTempDirectory("skills-data").toFile()
    private val discovery = SkillDiscovery(data)
    private val bom = "\uFEFF"

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
        data.deleteRecursively()
    }

    private fun skill(folder: File, text: String = "# A skill\n") {
        folder.mkdirs()
        File(folder, SKILL_FILE).writeText(text)
    }

    private fun names() = discovery.discover(root).map { it.name }

    @Test
    fun `a skill takes its name and description from the front matter`() {
        val text =
            """
            ---
            name: Code review
            description: "Reviews a diff"
            ---
            Body
            """.trimIndent()
        skill(File(root, ".claude/skills/review"), text)
        val found = discovery.discover(root)
        assertEquals(1, found.size)
        assertEquals("Code review", found[0].name)
        assertEquals("Reviews a diff", found[0].description)
        assertEquals(File(root, ".claude/skills/review").absoluteFile.path, found[0].id)
        assertTrue(found[0].project)
    }

    @Test
    fun `a skill without front matter is named after its folder`() {
        skill(File(root, "skills/plain"), "Just text, no front matter")
        skill(File(root, "skills/broken"), "---\nname: never closed\n")
        val found = discovery.discover(root)
        assertEquals(listOf("broken", "plain"), found.map { it.name })
        assertEquals(listOf("", ""), found.map { it.description })
    }

    @Test
    fun `a block description, CRLF and a byte order mark are read`() {
        val lines =
            """
            ---
            name: crlf
            description: >
              first line
              second line
            other: x
            ---
            Body
            """.trimIndent()
        skill(File(root, "skills/crlf"), bom + lines.replace("\n", "\r\n"))
        val found = discovery.discover(root).firstOrNull()
        assertEquals("crlf", found?.name)
        assertEquals("first line second line", found?.description)
    }

    @Test
    fun `skills are found three levels deep, not four`() {
        skill(File(root, "skills/a/b/c"))
        skill(File(root, "skills/a/b/c/d"))
        skill(File(root, "skills/w/x/y/z"))
        val ids = discovery.discover(root).map { it.id.removePrefix(root.absoluteFile.path + File.separator) }
        assertEquals(listOf("skills" + File.separator + "a" + File.separator + "b" + File.separator + "c"), ids)
    }

    @Test
    fun `a skill folder is not searched further`() {
        skill(File(root, "skills/outer"))
        skill(File(root, "skills/outer/inner"))
        assertEquals(listOf("outer"), names())
    }

    @Test
    fun `the skills of the data directory are listed with the project's, marked as not the project's`() {
        skill(File(data, "skills/mine"))
        skill(File(root, "skills/theirs"))
        val found = discovery.discover(root)
        assertEquals(listOf("mine", "theirs"), found.map { it.name })
        assertEquals(listOf(false, true), found.map { it.project })
    }

    @Test
    fun `the result is sorted by name whatever the order on disk`() {
        for (name in listOf("zeta", "Alpha", "mid")) skill(File(root, "skills/$name"))
        assertEquals(listOf("Alpha", "mid", "zeta"), names())
    }

    @Test
    fun `missing folders give no skill and nothing is created`() {
        val before = root.list().orEmpty().toSet() + data.list().orEmpty().toSet()
        assertEquals(emptyList(), discovery.discover(root))
        assertEquals(emptyList(), discovery.discover(File(root, "does-not-exist")))
        assertEquals(before, root.list().orEmpty().toSet() + data.list().orEmpty().toSet())
        assertFalse(File(data, "skills").exists())
    }

    @Test
    fun `a link to a folder of skills is followed and a loop ends`() {
        val outside = Files.createTempDirectory("skills-outside").toFile()
        try {
            skill(File(outside, "linked"))
            File(root, "skills").mkdirs()
            Files.createSymbolicLink(File(root, "skills/ln").toPath(), outside.toPath())
            Files.createSymbolicLink(File(outside, "loop").toPath(), File(root, "skills").toPath())
            assertTrue("linked" in names(), "Found: ${names()}")
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `only the head of a long SKILL_md is read`() {
        val head =
            """
            ---
            name: long
            ---
            """.trimIndent()
        val long = head + "\n" + "x".repeat(5_000_000)
        skill(File(root, "skills/long"), long)
        assertEquals(listOf("long"), names())
    }
}
