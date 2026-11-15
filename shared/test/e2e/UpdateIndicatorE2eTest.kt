package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.state.ReleaseInfo
import org.example.kotlineditor.state.ReleaseSource
import org.example.kotlineditor.state.UpdateNotifier
import org.example.kotlineditor.ui.AppTags
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TICK_MILLIS = 100L

/**
 * The icon that tells a newer release is published, with a stand-in of GitHub as the source of releases (never the
 * real network). The feature does not talk to the IDE's server, so it has no chaos test: the failures it can meet
 * (offline, rate limit, no release) are the "source fails" tests here. It changes nothing in the project.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateIndicatorE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }
    private val opened = CopyOnWriteArrayList<String>()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun notifier(current: String?, source: ReleaseSource) =
        UpdateNotifier(current, source, Duration.ofMillis(TICK_MILLIS), opened::add)

    private fun releases(vararg tags: String) = ReleaseSource { tags.map { ReleaseInfo(it, draft = false) } }

    @Test
    fun `a newer release shows the icon on the welcome screen, and a click opens its page`() =
        runComposeUiTest {
            launchApp(updates = notifier("0.2.0", releases("v0.1.0", "v0.3.0")))
            awaitTag(AppTags.UPDATE_AVAILABLE).performClick()
            awaitCondition { opened.isNotEmpty() }
            assertEquals(listOf("https://github.com/brandondurandpro/BStinger/releases/tag/v0.3.0"), opened.toList())
        }

    @Test
    fun `the icon is also in the status bar of a project, and changes nothing on disk`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            launchWithProject(project, updates = notifier("0.2.0", releases("v0.3.0")))
            awaitTag(AppTags.UPDATE_AVAILABLE)
            awaitTag(AppTags.STATUS_BAR)
            before.assertUnchanged(project)
        }

    @Test
    fun `no icon when the newest release is the same as the IDE or older`() =
        runComposeUiTest {
            val checked = AtomicInteger()
            val counting =
                ReleaseSource {
                    checked.incrementAndGet()
                    releases("v0.2.0", "v0.1.0").releases()
                }
            launchApp(updates = notifier("0.2.0", counting))
            awaitCondition { checked.get() >= 2 }
            assertEquals(0, onAllNodes(hasTestTag(AppTags.UPDATE_AVAILABLE)).fetchSemanticsNodes().size)
        }

    @Test
    fun `a failing source shows neither the icon nor an error, and the next check tries again`() =
        runComposeUiTest {
            val calls = AtomicInteger()
            val offline = AtomicBoolean(true)
            val flaky =
                ReleaseSource {
                    calls.incrementAndGet()
                    if (offline.get()) throw IOException("offline")
                    releases("v1.0.0").releases()
                }
            launchApp(updates = notifier("0.9.0", flaky))
            awaitCondition { calls.get() >= 2 }
            assertEquals(0, onAllNodes(hasTestTag(AppTags.UPDATE_AVAILABLE)).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodes(hasTestTag(AppTags.WELCOME_ERROR)).fetchSemanticsNodes().size)
            offline.set(false)
            awaitTag(AppTags.UPDATE_AVAILABLE)
        }

    @Test
    fun `a slow source leaves the IDE usable, then the icon arrives`() =
        runComposeUiTest {
            val release = CountDownLatch(1)
            val slow =
                ReleaseSource {
                    withContext(Dispatchers.IO) { release.await() }
                    releases("v2.0.0").releases()
                }
            launchApp(updates = notifier("1.0.0", slow))
            awaitTag(AppTags.WELCOME)
            assertEquals(0, onAllNodes(hasTestTag(AppTags.UPDATE_AVAILABLE)).fetchSemanticsNodes().size)
            // Still waiting for GitHub, the IDE answers: the settings open and close.
            awaitTag(AppTags.WELCOME_SETTINGS).performClick()
            awaitTag(AppTags.SETTINGS)
            awaitTag(AppTags.SETTINGS_CLOSE).performClick()
            awaitNoTag(AppTags.SETTINGS)
            release.countDown()
            awaitTag(AppTags.UPDATE_AVAILABLE)
        }
}
