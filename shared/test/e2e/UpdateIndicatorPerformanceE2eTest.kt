package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.GitHubReleases
import org.example.kotlineditor.state.ReleaseInfo
import org.example.kotlineditor.state.ReleaseSource
import org.example.kotlineditor.state.UpdateNotifier
import org.example.kotlineditor.ui.AppTags
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals

private const val RELEASES = 5_000
private const val PARSE_BUDGET_MILLIS = 300L
private const val SHOW_BUDGET_MILLIS = 1_000L

/** The cost of the update check on a repository with thousands of releases: parsing them, then showing the icon. */
@OptIn(ExperimentalTestApi::class)
class UpdateIndicatorPerformanceE2eTest {
    private val tags = (1..RELEASES).map { "v0.$it.0" }

    @Test
    fun `thousands of releases are parsed and compared within budget`() {
        val body = tags.joinToString(",", "[", "]") { """{"tag_name":"$it","draft":false,"body":"notes"}""" }
        measure("parse $RELEASES releases", PARSE_BUDGET_MILLIS) {
            assertEquals(RELEASES, GitHubReleases.parseReleases(body).size)
        }
    }

    @Test
    fun `the icon shows within budget when the repository has thousands of releases`() =
        runComposeUiTest {
            val gate = CountDownLatch(1)
            val source =
                ReleaseSource {
                    withContext(Dispatchers.IO) { gate.await() }
                    tags.map { ReleaseInfo(it, draft = false) }
                }
            val notifier = UpdateNotifier("0.1.0", source, Duration.ofHours(1), {})
            launchApp(updates = notifier)
            measure("icon with $RELEASES releases", SHOW_BUDGET_MILLIS) {
                gate.countDown()
                awaitTag(AppTags.UPDATE_AVAILABLE)
                renderFrame()
            }
            assertEquals("v0.$RELEASES.0", notifier.available?.tag)
        }
}
