package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerScriptsTest {
    @Test
    fun `the scripts are read in the order of the file`() {
        val json = """{"name": "a/b", "scripts": {"test": "phpunit", "lint": ["phpcs", "phpstan"], "cs:fix": "x"}}"""
        assertEquals(listOf("test", "lint", "cs:fix"), composerScriptNames(json))
    }

    @Test
    fun `the scripts composer runs by itself are left out`() {
        val json = """{"scripts": {"post-install-cmd": "a", "pre-update-cmd": "b", "build": "c"}}"""
        assertEquals(listOf("build"), composerScriptNames(json))
    }

    @Test
    fun `a file without scripts, or that is not JSON, has none`() {
        assertTrue(composerScriptNames("""{"name": "a/b"}""").isEmpty())
        assertTrue(composerScriptNames("not json").isEmpty())
        assertTrue(composerScriptNames("").isEmpty())
        assertTrue(composerScriptNames("[1, 2]").isEmpty())
        assertTrue(composerScriptNames("""{"scripts": "oops"}""").isEmpty())
    }

    @Test
    fun `a script becomes a configuration run in the directory of its project`() {
        val configurations = composerConfigurations("services/api", listOf("test", "fix style"), windows = false)
        assertEquals(
            listOf("Composer: test (services/api)", "Composer: fix style (services/api)"),
            configurations.map { it.name },
        )
        assertEquals(
            listOf("composer run-script test", "composer run-script \"fix style\""),
            configurations.map { it.commandLine },
        )
        assertEquals(listOf("services/api", "services/api"), configurations.map { it.workingDirectory })
        assertEquals(2, configurations.map { it.id }.toSet().size)
    }

    @Test
    fun `the project at the root has no directory in its names`() {
        val configuration = composerConfigurations("", listOf("test"), windows = true).single()
        assertEquals("Composer: test", configuration.name)
        assertEquals("composer.bat run-script test", configuration.commandLine)
    }

    @Test
    fun `the command line of a configuration splits back into its words`() {
        val configuration = composerConfigurations("", listOf("fix style"), windows = false).single()
        assertEquals(listOf("composer", "run-script", "fix style"), splitCommandLine(configuration.commandLine))
    }

    @Test
    fun `only the composer json of the project itself counts`() {
        assertTrue(isProjectComposerFile("composer.json"))
        assertTrue(isProjectComposerFile("services/api/composer.json"))
        assertFalse(isProjectComposerFile("vendor/lib/composer.json"))
        assertFalse(isProjectComposerFile("services/node_modules/x/composer.json"))
        assertFalse(isProjectComposerFile("composer.lock"))
        assertFalse(isProjectComposerFile("docs/composer.json.md"))
    }
}
