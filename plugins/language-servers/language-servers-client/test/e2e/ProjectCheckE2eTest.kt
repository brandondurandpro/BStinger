package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileDiagnostics
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The check of the whole project (`vue-tsc`), from the language servers window. */
@OptIn(ExperimentalTestApi::class)
class ProjectCheckE2eTest {
    private val project = FakePhpServers.webProject().apply { commitAll() }
    private var result: ProjectCheckResult = problems(project, 2)
    private val servers = FakePhpServers(check = { result })

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `the check lists the problems of the project, files open or not`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            openPanel(app)
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "2 problems in 2 files" }
            val lines = textOf(LanguageServerTags.PROJECT_CHECK_PROBLEMS)
            assertContains(lines, "src/closed0.vue:4:3  Type error 0")
            assertContains(lines, "src/closed1.vue:4:3  Type error 1")
        }

    @Test
    fun `a project that cannot be checked says why and lists nothing`() =
        runComposeUiTest {
            result = ProjectCheckResult(FeatureStatus.NotReady, "", "No tsconfig.json in the project", emptyList())
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            openPanel(app)
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "No tsconfig.json in the project" }
            awaitNoTag(LanguageServerTags.PROJECT_CHECK_PROBLEMS)
        }

    @Test
    fun `a project without problem says so`() =
        runComposeUiTest {
            result = ProjectCheckResult(FeatureStatus.Ready, "vue-tsc", "No problem found", emptyList())
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            openPanel(app)
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "No problem found" }
            awaitNoTag(LanguageServerTags.PROJECT_CHECK_PROBLEMS)
        }

    @Test
    fun `checking the project changes nothing in it`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            openPanel(app)
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "2 problems in 2 files" }
            before.assertUnchanged(project)
        }

    private fun androidx.compose.ui.test.ComposeUiTest.openPanel(app: E2eApp) {
        val running = awaitServer(app) { it.state == LanguageServerState.Running }
        awaitTag(LanguageServerTags.statusLanguageServer(running.id)).performClick()
        awaitTag(LanguageServerTags.LANGUAGE_SERVERS)
        assertEquals(1, app.languageServers.size)
    }
}

internal fun problems(project: SampleProject, count: Int, perFile: Int = 1): ProjectCheckResult {
    val files =
        (0 until count).map { index ->
            val diagnostics =
                (0 until perFile).map { number ->
                    val where = TextRange(3, 2, 3, 6)
                    Diagnostic(where, DiagnosticSeverity.Error, "Type error $index", "vue-tsc", "TS${2322 + number}")
                }
            FileDiagnostics(project.root.resolve("src/closed$index.vue").toString(), diagnostics)
        }
    return ProjectCheckResult(FeatureStatus.Ready, "vue-tsc", "$count problems in $count files", files)
}
