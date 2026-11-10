package org.example.kotlineditor.lsp

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The servers the files of a project need, without starting any. */
class LanguageServerTargetsTest {
    private val project = createTempDirectory("targets-project")
    private val data = createTempDirectory("targets-data")
    private val targets = LanguageServerTargets(LanguageServerSettings(dataDirectory = data, toolsDirectory = data))

    @AfterTest
    fun cleanup() {
        project.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }

    private fun idsOf(vararg files: String): List<String> =
        targets.targetsOf(project.toString(), files.map { project.resolve(it).toString() }).first.map { it.id }

    @Test
    fun `a project without Vue runs the TypeScript server as it is`() {
        assertEquals(listOf("typescript-language-server@$project"), idsOf("a.ts", "b.tsx"))
    }

    @Test
    fun `the first Vue file makes it a Vue project, whose TypeScript server loads the Vue plugin and stays so`() {
        val plain = "typescript-language-server@$project"
        val withVue = "typescript-language-server+vue@$project"
        assertEquals(listOf(plain), idsOf("a.ts"))
        assertTrue(withVue in idsOf("a.ts", "App.vue"), "ids: ${idsOf("a.ts", "App.vue")}")
        assertTrue("vue-language-server@$project" in idsOf("App.vue"))
        // The Vue file is closed: the server is not restarted without the plugin.
        assertEquals(listOf(withVue), idsOf("a.ts"))
    }

    @Test
    fun `a package that depends on vue is a Vue project from the start`() {
        Files.writeString(project.resolve("package.json"), """{"dependencies": {"vue": "3"}}""")
        assertEquals(listOf("typescript-language-server+vue@$project"), idsOf("a.ts"))
    }
}
