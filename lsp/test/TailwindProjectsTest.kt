package org.example.kotlineditor.lsp

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TailwindProjectsTest {
    private val project = createTempDirectory("tailwind-projects")

    @AfterTest
    fun cleanup() {
        project.toFile().deleteRecursively()
    }

    private fun write(relative: String, text: String = "{}") {
        val file = project.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun rootOf(relative: String): String? {
        val file = project.resolve(relative).toString()
        return TailwindProjects.rootOf(file, project.toString())
    }

    @Test
    fun `a project without tailwind has no root`() {
        write("package.json", """{"dependencies": {"react": "18"}}""")
        write("src/view.tsx")
        assertNull(rootOf("src/view.tsx"))
    }

    @Test
    fun `a config at the project root makes the project the root`() {
        write("tailwind.config.js")
        write("src/view.tsx")
        assertEquals(project.toString(), rootOf("src/view.tsx"))
    }

    @Test
    fun `the closest config wins in a monorepo, and a package without one has none`() {
        write("apps/web/tailwind.config.ts")
        write("apps/web/src/view.tsx")
        write("apps/api/src/server.ts")
        assertEquals(project.resolve("apps/web").toString(), rootOf("apps/web/src/view.tsx"))
        assertNull(rootOf("apps/api/src/server.ts"))
    }

    @Test
    fun `a dependency on tailwindcss is enough, as version 4 has no config file`() {
        write("package.json", """{"devDependencies": {"tailwindcss": "^4.0.0"}}""")
        write("src/view.tsx")
        assertEquals(project.toString(), rootOf("src/view.tsx"))
    }
}
