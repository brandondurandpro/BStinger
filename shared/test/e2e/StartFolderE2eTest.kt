package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `ide <folder>` starts the IDE on that folder, as if it had been picked on the welcome screen. */
@OptIn(ExperimentalTestApi::class)
class StartFolderE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the folder given at startup opens as a project, changing nothing on disk`() =
        runComposeUiTest {
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchApp(startProject = project.path)
            awaitCondition { app.state.session.project != null }
            assertEquals(project.path, assertNotNull(app.state.session.project).root.path)
            before.assertUnchanged(project)
        }

    @Test
    fun `a folder that does not exist shows an error and stays on the welcome screen`() =
        runComposeUiTest {
            val app = launchApp(startProject = project.path + "-missing")
            awaitCondition { app.state.session.error != null }
            assertNull(app.state.session.project)
        }
}
