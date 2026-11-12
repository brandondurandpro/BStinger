package org.example.kotlineditor.lsp.supervisor

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Which processes [OrphanReaper.killTree] and [OrphanReaper.ownDescendants] take for the children of a process. */
class ProcessTreeTest {
    private val started = ArrayList<Process>()

    @AfterTest
    fun cleanup() {
        started.forEach { it.destroyForcibly() }
    }

    private fun sleeper(): List<String> =
        if (windows) listOf("ping", "-n", "60", "127.0.0.1") else listOf("sleep", "60")

    private fun start(command: List<String>): Process = ProcessBuilder(command).start().also { started += it }

    @Test
    fun `the descendants of a running process are its children`() {
        val parent = start(if (windows) listOf("cmd.exe", "/c") + sleeper() else listOf("sh", "-c", "sleep 60; true"))
        val handle = parent.toHandle()
        val deadline = System.currentTimeMillis() + 10_000
        while (handle.children().count() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val children = handle.children().use { it.toList() }
        val own = OrphanReaper.ownDescendants(handle, OrphanReaper.startOf(handle))
        assertTrue(own.containsAll(children), "$own should hold $children")
        assertEquals(emptyList(), OrphanReaper.ownDescendants(handle, 0L), "no start time, no descendant")
        OrphanReaper.killTree(handle)
        assertTrue(parent.waitFor(10, TimeUnit.SECONDS))
        // Completes once the child exited; fails after 10 s.
        children.forEach { child -> child.onExit().get(10, TimeUnit.SECONDS) }
    }

    // A process of a made-up tree: its pid, the pid its parent had, and when it started.
    private data class Listed(val pid: Long, val parentPid: Long, val start: Long)

    private fun ownOf(root: Listed, processes: List<Listed>): List<Long> {
        val own =
            ownTree(
                root = root,
                rootStart = root.start,
                childrenOf = { parent -> processes.filter { it.parentPid == parent.pid } },
                startOf = { it.start },
            )
        return own.map { it.pid }
    }

    @Test
    fun `what a stranger with the pid of the parent started is not taken, however recent`() {
        // The agent has exited; its pid, 40, was once the pid of the launcher of a CI runner, which still names it
        // as its parent. The runner started long before the agent, the Git of another test a moment ago.
        val agent = Listed(pid = 40, parentPid = 7, start = 1_000)
        val runner = Listed(pid = 50, parentPid = 40, start = 10)
        val otherTests = Listed(pid = 60, parentPid = 50, start = 500)
        val theirGit = Listed(pid = 70, parentPid = 60, start = 2_000)
        val node = Listed(pid = 80, parentPid = 40, start = 1_100)
        val helper = Listed(pid = 90, parentPid = 80, start = 1_200)
        val processes = listOf(runner, otherTests, theirGit, node, helper)

        assertEquals(listOf(80L, 90L), ownOf(agent, processes))
    }

    @Test
    fun `a process whose start is unknown is not taken, and a loop of parent pids ends`() {
        val root = Listed(pid = 40, parentPid = 90, start = 1_000)
        val unknown = Listed(pid = 50, parentPid = 40, start = 0)
        val belowUnknown = Listed(pid = 60, parentPid = 50, start = 1_500)
        val child = Listed(pid = 80, parentPid = 40, start = 1_000)
        val grandchild = Listed(pid = 90, parentPid = 80, start = 1_000)
        val processes = listOf(root, unknown, belowUnknown, child, grandchild)

        assertEquals(listOf(80L, 90L), ownOf(root, processes))
    }
}
