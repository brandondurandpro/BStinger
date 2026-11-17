package org.example.kotlineditor.harness

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.testkit.skills.SkillRepositories
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MATT = "mattpocock/skills"
private const val ANTHROPIC = "anthropics/skills"

/** The skill catalog: repositories downloaded once, listed with their descriptions, installed into the data directory. */
class SkillCatalogTest {
    private val data = Files.createTempDirectory("catalog-data").toFile()
    private val repositories = SkillRepositories()

    @AfterTest
    fun cleanup() {
        repositories.close()
        data.deleteRecursively()
    }

    private fun catalog(vararg sources: String) = SkillCatalog(data, sources.toList(), repositories.archiveUrl)

    private fun mattSkills() =
        repositories.skill(MATT, "skills/engineering/tdd", "tdd", "Red-green-refactor", mapOf("tests.md" to "How to test")) +
            repositories.skill(MATT, "skills/productivity/grill-me", "grill-me", "Get interviewed") +
            mapOf("LICENSE" to "MIT License", "README.md" to "not a skill", "scripts/tool.sh" to "echo not in a skill")

    @Test
    fun `the skills of a repository are listed with their names and descriptions, none installed`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val listing = catalog(MATT).list(refresh = false)
            assertEquals(emptyMap(), listing.failures)
            assertEquals(listOf("grill-me", "tdd"), listing.skills.map { it.name })
            assertEquals(listOf("skills/productivity/grill-me", "skills/engineering/tdd"), listing.skills.map { it.path })
            assertEquals("Red-green-refactor", listing.skills.firstOrNull { it.name == "tdd" }?.description)
            assertTrue(listing.skills.none { it.installed })
            assertEquals(setOf(MATT), listing.skills.map { it.source }.toSet())
        }

    @Test
    fun `a repository is downloaded once, listed again without the network, and again when refreshed`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val catalog = catalog(MATT)
            catalog.list(refresh = false)
            catalog.list(refresh = false)
            assertEquals(1, repositories.requests)
            repositories.add(MATT, mattSkills() + repositories.skill(MATT, "skills/misc/new-one", "new-one", "Added later"))
            val refreshed = catalog.list(refresh = true)
            assertEquals(2, repositories.requests)
            assertTrue(refreshed.skills.any { it.name == "new-one" })
        }

    @Test
    fun `a repository that cannot be read is reported and the others are listed`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val listing = catalog(MATT, ANTHROPIC).list(refresh = false)
            assertEquals(listOf("grill-me", "tdd"), listing.skills.map { it.name })
            assertEquals(setOf(ANTHROPIC), listing.failures.keys)
            assertTrue(listing.failures.getValue(ANTHROPIC).contains("Cannot download"))
        }

    @Test
    fun `a truncated archive fails its repository and leaves no half-downloaded repository`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            repositories.truncate(MATT)
            val listing = catalog(MATT).list(refresh = false)
            assertEquals(setOf(MATT), listing.failures.keys)
            assertEquals(emptyList(), File(data, "skill-catalog").listFiles().orEmpty().toList())
        }

    @Test
    fun `a failed refresh keeps the repository downloaded before`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val catalog = catalog(MATT)
            catalog.list(refresh = false)
            repositories.truncate(MATT)
            val listing = catalog.list(refresh = true)
            assertEquals(setOf(MATT), listing.failures.keys)
            assertEquals(listOf("grill-me", "tdd"), catalog.list(refresh = false).skills.map { it.name })
        }

    @Test
    fun `installing copies the folder of the skill, its extra files and the licence, where the agents find it`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val catalog = catalog(MATT)
            val installed = catalog.install(MATT, "skills/engineering/tdd")
            assertEquals("tdd", installed.name)
            val folder = File(data, "skills/mattpocock-skills/engineering--tdd")
            assertEquals(folder.path, installed.id)
            assertEquals("How to test", File(folder, "tests.md").readText())
            assertEquals("MIT License", File(data, "skills/mattpocock-skills/LICENSE").readText())
            assertFalse(File(data, "skills/mattpocock-skills/engineering--grill-me").exists())
            assertEquals(listOf("tdd"), SkillDiscovery(data).discover(File(data, "no-project")).map { it.name })
            assertTrue(catalog.list(refresh = false).skills.first { it.name == "tdd" }.installed)
        }

    @Test
    fun `installing again replaces the skill, without the files it no longer has`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val catalog = catalog(MATT)
            catalog.install(MATT, "skills/engineering/tdd")
            val folder = File(data, "skills/mattpocock-skills/engineering--tdd")
            File(folder, "mine.md").writeText("added by the user")
            repositories.add(MATT, repositories.skill(MATT, "skills/engineering/tdd", "tdd", "Second version"))
            catalog.list(refresh = true)
            val again = catalog.install(MATT, "skills/engineering/tdd")
            assertEquals("Second version", again.description)
            assertFalse(File(folder, "tests.md").exists())
            assertFalse(File(folder, "mine.md").exists())
        }

    @Test
    fun `a path that leaves the repository or holds no skill is refused, as is an unknown repository`() =
        runBlocking {
            repositories.add(MATT, mattSkills())
            val catalog = catalog(MATT)
            assertFailsWith<IOException> { catalog.install(MATT, "../../etc") }
            assertFailsWith<IOException> { catalog.install(MATT, "skills") }
            assertFailsWith<IOException> { catalog.install(MATT, "README.md") }
            assertFailsWith<IOException> { catalog.install("evil/skills", "skills/engineering/tdd") }
            assertEquals(false, File(data, "skills").exists())
        }

    @Test
    fun `symbolic links of a repository are left out, they do not break the download`() =
        runBlocking {
            repositories.add(MATT, mattSkills(), links = mapOf(".claude/skills" to "../skills"))
            val listing = catalog(MATT).list(refresh = false)
            assertEquals(emptyMap(), listing.failures)
            assertEquals(2, listing.skills.size)
        }

    @Test
    fun `two skills with the same folder name in different places are installed apart`() =
        runBlocking {
            repositories.add(
                MATT,
                repositories.skill(MATT, "skills/engineering/plan", "plan", "One") + repositories.skill(MATT, "skills/misc/plan", "plan", "Two"),
            )
            val catalog = catalog(MATT)
            catalog.install(MATT, "skills/engineering/plan")
            val listing = catalog.list(refresh = false)
            assertEquals(listOf(true, false), listing.skills.sortedBy { it.path }.map { it.installed })
        }
}
