package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.UpdateState
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A release is about 130 MB: this one is 96 MB of data that does not compress.
private const val ASSET_BYTES = 96 * 1024 * 1024
private const val OPEN_BUDGET_MILLIS = 1_000L
private const val UPDATE_BUDGET_MILLIS = 10_000L
private const val FRAME_BUDGET_MILLIS = 1_000L
private const val UPDATE_TIMEOUT = 120_000L

/**
 * The cost of updating with a release of the size of a real one, from a loopback server: the dialog opens within
 * budget, the whole download (streamed, hashed on the way), its verification and its unpacking finish within budget,
 * and the UI stays responsive while the progress is drawn.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdatePerformanceE2eTest {
    private var rig: UpdateRig? = null

    @AfterTest
    fun cleanup() {
        rig?.close()
    }

    @Test
    fun `a release of the size of a real one is downloaded and staged within budget, the UI staying responsive`() =
        runComposeUiTest {
            val big = UpdateRig(padBytes = ASSET_BYTES).also { rig = it }
            launchApp(updates = big.notifier)
            measure("update dialog opens", OPEN_BUDGET_MILLIS) {
                awaitTag(AppTags.UPDATE_AVAILABLE).performClick()
                awaitTag(AppTags.UPDATE_DIALOG)
                renderFrame()
            }
            var worstFrame = 0L
            var progressShown = false
            measure("download, verify and stage ${ASSET_BYTES / MEGABYTE} MB", UPDATE_BUDGET_MILLIS) {
                awaitTag(AppTags.UPDATE_INSTALL).performClick()
                awaitCondition(UPDATE_TIMEOUT) {
                    val frame = costOf { renderFrame() }
                    worstFrame = maxOf(worstFrame, frame.millis)
                    progressShown = progressShown || big.updater.state is UpdateState.Downloading
                    big.updater.state is UpdateState.Ready
                }
            }
            println("PERF worst frame while updating: $worstFrame ms (budget $FRAME_BUDGET_MILLIS ms)")
            assertTrue(worstFrame <= FRAME_BUDGET_MILLIS, "a frame took $worstFrame ms while the update ran")
            assertTrue(progressShown, "the progress was shown while downloading")
            assertEquals(1, big.assetRequests())
            assertEquals("old", big.install.version())
        }

    private companion object {
        const val MEGABYTE = 1024 * 1024
    }
}
