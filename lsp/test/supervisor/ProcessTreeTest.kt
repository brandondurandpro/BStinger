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
}
