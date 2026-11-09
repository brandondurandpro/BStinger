package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** A Vue project without the `vue` package: the Language Servers window says why the templates are poorly served. */
@OptIn(ExperimentalTestApi::class)
class VueSetupNoteE2eTest {
    private val project = FakePhpServers.webProject()
    private val servers = FakePhpServers()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `the window warns that vue is not installed, and stops when it is`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/App.vue")
            val server = awaitServer(app) { it.setupNote != null }
            awaitTag(LanguageServerTags.statusLanguageServer(server.id)).performClick()
            awaitTag(LanguageServerTags.SETUP_NOTE).assertTextContains("node_modules/vue", substring = true)
        }
}
