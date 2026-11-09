package org.example.kotlineditor.lsp

import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.RuntimeStatus
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.lsp.provision.WebServers
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.FeatureStatus
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OUTPUT =
    """src/a.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'.
src/App.vue(10,5): error TS2339: Property 'nope' does not exist on type 'string'.
  The type 'string' has no such member.
src/a.ts(9,1): warning TS6133: 'x' is declared but never used.
Found 3 errors in 2 files.
"""

/** `vue-tsc` output read as problems, and the real checker on a small project. */
class ProjectCheckerTest {
    private val project = createTempDirectory("check-project")
    private val tools = Paths.get(System.getProperty("user.home"), ".cache", "kotlin-editor", "test-tools")

    @AfterTest
    fun cleanup() {
        project.toFile().deleteRecursively()
    }

    @Test
    fun `the output is read as problems by file, with their continuation lines`() {
        val result = TscOutput.parse(OUTPUT, project)
        assertEquals(FeatureStatus.Ready, result.status)
        assertEquals("3 problems in 2 files", result.summary)
        val paths = result.files.map { it.path }
        val expected = listOf("src/a.ts", "src/App.vue").map { project.resolve(it).toString() }
        assertEquals(expected, paths)
        val first = result.files.first().diagnostics
        assertEquals(2, first.size)
        assertEquals(2, first.first().range.startLine)
        assertEquals(6, first.first().range.startColumn)
        assertEquals("TS2322", first.first().code)
        assertEquals(DiagnosticSeverity.Warning, first.last().severity)
        val lastFile = result.files.last()
        val vue = lastFile.diagnostics.single()
        assertTrue(vue.message.endsWith("The type 'string' has no such member."), vue.message)
    }

    @Test
    fun `an output without problem says so`() {
        val result = TscOutput.parse("", project)
        assertEquals(FeatureStatus.Ready, result.status)
        assertEquals("No problem found", result.summary)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `a project without tsconfig is not checked, and says why`() {
        val result = ProjectChecker(ToolDownloader(tools)).check(project.toString())
        assertEquals(FeatureStatus.NotReady, result.status)
        assertEquals("No tsconfig.json in the project", result.summary)
    }

    @Test
    fun `the real vue-tsc reports the errors of a ts file and of a vue file, and writes nothing`() {
        val node = WebServers.typescriptLanguageServer.requirements.first()
        val detector = RuntimeDetector()
        val found = detector.detect(node.runtime, node.minimumVersion, emptyList())
        if (found !is RuntimeStatus.Found) return
        Files.writeString(project.resolve("tsconfig.json"), TSCONFIG)
        Files.writeString(project.resolve("a.ts"), "export const n: number = 'text'\n")
        Files.writeString(project.resolve("App.vue"), COMPONENT)
        val before = Files.list(project).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        val result = ProjectChecker(ToolDownloader(tools)).check(project.toString())
        assertEquals(FeatureStatus.Ready, result.status, result.summary)
        val names = result.files.map { Paths.get(it.path).fileName.toString() }.sorted()
        assertTrue("App.vue" in names && "a.ts" in names, result.summary)
        val after = Files.list(project).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(before, after)
    }

    private companion object {
        val COMPONENT = listOf("<script setup lang='ts'>", "const s: string = 1", "</script>").joinToString("\n")
        const val TSCONFIG = """{"compilerOptions": {"strict": true, "noEmit": true}, "include": ["*.ts", "*.vue"]}"""
    }
}
