package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Remote development on a project big enough for a per-file or per-tree slowdown to show. */
@OptIn(ExperimentalTestApi::class)
class RemoteDevelopmentPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            val big = (1..5_000).joinToString("\n") { """val line$it = ${'"'}remote $it${'"'}""" }
            file("big.txt", big)
            file("src/main/kotlin/App.kt", SampleProject.APP_KT)
            repeat(200) {
                file("src/main/kotlin/File$it.kt", """package demo${'\n'}${'\n'}class File$it${'\n'}""")
            }
        }

    @BeforeTest
    fun prepare() {
        RemoteTestEnvironment.prepare()
    }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `connecting and opening a large remote file over SSH stays interactive`() =
        remoteUiTest {
            launchApp()

            measure("remote connect and project tree", budgetMillis = 60_000) {
                connectRemoteTo(RemoteTestEnvironment, project.path)
                awaitTag(Tags.treeRow("src"), timeout = 60_000)
                renderFrame()
            }
            measure("open a 5000-line remote file", budgetMillis = 15_000, budgetMegabytes = 512) {
                openFromTree("big.txt")
                renderFrame()
            }
            measure("open another remote file after the first", budgetMillis = 10_000, budgetMegabytes = 256) {
                openFromTree("src/main/kotlin/App.kt")
                renderFrame()
            }
        }

    @Test
    fun `listing a thousand remote folders stays interactive`() {
        if (!RemoteTestEnvironment.available) return
        val many = Files.createTempDirectory(RemoteTestEnvironment.home, "many-")
        try {
            repeat(1_000) { Files.createDirectory(many.resolve("dir$it")) }
            remoteUiTest {
                launchApp()
                connectRemoteTo(RemoteTestEnvironment, null)
                awaitTag(Tags.FOLDER_PICKER, timeout = 60_000)
                measure("list 1000 remote folders", budgetMillis = 10_000, budgetMegabytes = 256) {
                    onNodeWithTag(ItemTags.folderEntry(many.toString())).performClick()
                    awaitTag(Tags.FOLDER_PICKER_COUNT)
                    onNodeWithTag(Tags.FOLDER_PICKER_COUNT)
                        .assertTextContains("1000 folders", substring = true)
                    renderFrame()
                }
            }
        } finally {
            many.toFile().deleteRecursively()
        }
    }
}
