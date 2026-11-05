package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Closing the IDE saves every modified file before it closes. */
@OptIn(ExperimentalTestApi::class)
class ExitE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `closing the IDE saves every modified file, then closes without asking anything`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val documents = EDITED_ON_EXIT.associateWith { edit(app, it) }
            val before = DiskSnapshot.take(project)
            val closed = closeWindow(app)
            awaitCondition { closed.get() }
            assertEquals(0, onAllNodes(hasTestTag(AppTags.UNSAVED_DIALOG)).fetchSemanticsNodes().size)
            for ((path, document) in documents) assertEquals(document.text(), project.read(path))
            before.assertUnchanged(project, except = EDITED_ON_EXIT.toSet())
        }
}
