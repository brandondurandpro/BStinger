package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageScriptsTest {
    @Test
    fun `the scripts are read in the order of the file`() {
        val json = """{"name": "app", "scripts": {"dev": "vite", "build": "vite build", "test:unit": "vitest"}}"""
        assertEquals(listOf("dev", "build", "test:unit"), packageScriptNames(json))
    }

    @Test
    fun `a file without scripts, or that is not JSON, has none`() {
        assertTrue(packageScriptNames("""{"name": "app"}""").isEmpty())
        assertTrue(packageScriptNames("not json").isEmpty())
        assertTrue(packageScriptNames("").isEmpty())
        assertTrue(packageScriptNames("""{"scripts": []}""").isEmpty())
    }

    @Test
    fun `the lock file picks the package manager, npm by default`() {
        assertEquals(PackageManager.Pnpm, packageManagerOf(setOf("package.json", "pnpm-lock.yaml")))
        assertEquals(PackageManager.Yarn, packageManagerOf(setOf("yarn.lock")))
        assertEquals(PackageManager.Bun, packageManagerOf(setOf("bun.lockb")))
        assertEquals(PackageManager.Npm, packageManagerOf(setOf("package-lock.json")))
        assertEquals(PackageManager.Npm, packageManagerOf(setOf("package.json")))
    }

    @Test
    fun `a script becomes a configuration run in the directory of its project`() {
        val configurations = packageConfigurations("web", listOf("dev", "fix style"), PackageManager.Pnpm, false)
        assertEquals(listOf("pnpm: dev", "pnpm: fix style"), configurations.map { it.name })
        assertEquals(listOf("pnpm run dev", "pnpm run \"fix style\""), configurations.map { it.commandLine })
        assertEquals(listOf("web", "web"), configurations.map { it.workingDirectory })
        assertEquals(2, configurations.map { it.id }.toSet().size)
    }

    @Test
    fun `windows runs the cmd shims, except for bun`() {
        val npm = packageConfigurations("", listOf("dev"), PackageManager.Npm, windows = true).single()
        assertEquals("npm.cmd run dev", npm.commandLine)
        assertEquals("npm: dev", npm.name)
        val bun = packageConfigurations("", listOf("dev"), PackageManager.Bun, windows = true).single()
        assertEquals("bun run dev", bun.commandLine)
    }

    @Test
    fun `only the package json of the project itself counts`() {
        assertTrue(isProjectPackageFile("package.json"))
        assertTrue(isProjectPackageFile("web/package.json"))
        assertFalse(isProjectPackageFile("node_modules/left-pad/package.json"))
        assertFalse(isProjectPackageFile("web/node_modules/x/package.json"))
        assertFalse(isProjectPackageFile("package-lock.json"))
    }
}
