package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.model.DiskChanges
import org.example.kotlineditor.model.DiskCursor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Long enough for a change to arrive on a slow CI runner, short enough for a poll that waits for none.
private const val HOLD_MS = 5_000L
private const val SHORT_HOLD_MS = 300L
private const val CHANGES = 20
private const val TOLD_POLL_MS = 20L

class ProjectWatchesTest {
    private val root: Path = Files.createTempDirectory("project-watches").toRealPath()
    private val told = CopyOnWriteArrayList<Pair<String, DiskChange>>()
    private var watches = watchesOf(WatchSettings(holdMs = HOLD_MS, settleMs = 50))

    private fun watchesOf(settings: WatchSettings) = ProjectWatches(settings) { told += it }

    @AfterTest
    fun cleanup() {
        watches.close()
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String, text: String = ""): Path {
        val path = root.resolve(relative)
        path.parent.createDirectories()
        path.writeText(text)
        return path
    }

    private fun poll(since: DiskCursor): DiskChanges = runBlocking { watches.changes(root.toString(), since) }

    // The cursor of a watch that runs, every directory registered.
    // The language servers are told once the watcher is idle, which may come after a poll already answered.
    private fun awaitTold(change: (Pair<String, DiskChange>) -> Boolean) {
        val deadline = System.currentTimeMillis() + HOLD_MS
        while (told.none(change) && System.currentTimeMillis() < deadline) Thread.sleep(TOLD_POLL_MS)
        assertTrue(told.any(change), "$told")
    }

    private fun started(): DiskCursor = poll(DiskCursor.START).cursor

    private class Seen(val paths: Set<String>, val cursor: DiskCursor)

    // Polls until [path] is reported (a burst may come in several answers): every path seen, and the last cursor.
    private fun pollUntil(since: DiskCursor, path: Path): Seen {
        val seen = HashSet<String>()
        var cursor = since
        repeat(10) {
            val answer = poll(cursor)
            assertTrue(answer.complete)
            seen += answer.paths
            cursor = answer.cursor
            if (path.toString() in seen) return Seen(seen, cursor)
        }
        error("$path was never reported, only $seen")
    }

    @Test
    fun `the first poll gives the position without changes`() {
        val first = poll(DiskCursor.START)
        assertFalse(first.complete)
        assertTrue(first.paths.isEmpty())
    }

    @Test
    fun `a file created by another program is reported, and told to the language servers`() {
        val cursor = started()
        val created = file("src/Added.kt", "class Added")
        assertTrue(created.toString() in pollUntil(cursor, created).paths)
        awaitTold { it.first == created.toString() && it.second != DiskChange.Deleted }
    }

    @Test
    fun `a file of a directory created after the watch started is reported`() {
        val cursor = started()
        root.resolve("late/deeper").createDirectories()
        // Reported once registered, with the directories below.
        val registered = pollUntil(cursor, root.resolve("late/deeper"))
        val created = file("late/deeper/Inside.kt")
        pollUntil(registered.cursor, created)
    }

    @Test
    fun `a deleted file is reported`() {
        val gone = file("Gone.kt")
        val cursor = started()
        Files.delete(gone)
        pollUntil(cursor, gone)
        awaitTold { it.first == gone.toString() && it.second == DiskChange.Deleted }
    }

    @Test
    fun `the branch and the index of git are reported, not its objects`() {
        file(".git/HEAD", "ref: refs/heads/main\n")
        file(".git/objects/ab/cdef")
        val cursor = started()
        file(".git/objects/ab/123456")
        file(".git/objects/cd/7890")
        val head = file(".git/HEAD", "ref: refs/heads/other\n")
        val seen = pollUntil(cursor, head).paths
        assertTrue(seen.none { "objects" in it }, "$seen")
        assertTrue(told.none { ".git" in it.first }, "$told")
    }

    @Test
    fun `the entries of a build directory are reported, not what is below them`() {
        root.resolve("build/classes").createDirectories()
        val cursor = started()
        file("build/classes/deep/C.class")
        val witness = file("Witness.kt")
        val seen = pollUntil(cursor, witness)
        assertTrue(seen.paths.none { "C.class" in it }, "${seen.paths}")
        val output = file("build/output.txt")
        pollUntil(seen.cursor, output)
    }

    @Test
    fun `a poll without changes answers after a while with none`() {
        watches.close()
        watches = watchesOf(WatchSettings(holdMs = SHORT_HOLD_MS))
        val cursor = started()
        val answer = poll(cursor)
        assertTrue(answer.complete)
        assertTrue(answer.paths.isEmpty())
        assertEquals(cursor, answer.cursor)
    }

    @Test
    fun `a cursor of another watch is told that anything may have changed`() {
        val cursor = started()
        watches.close()
        watches = watchesOf(WatchSettings(holdMs = SHORT_HOLD_MS))
        val answer = poll(cursor)
        assertFalse(answer.complete)
        assertTrue(answer.cursor.watch != cursor.watch)
    }

    @Test
    fun `a poll that missed more changes than are kept is told that anything may have changed`() {
        watches.close()
        watches = watchesOf(WatchSettings(holdMs = HOLD_MS, settleMs = 50, maxKept = 5))
        val cursor = started()
        repeat(CHANGES) { file("F$it.kt") }
        // Each file is at least one change: wait until they all arrived.
        var latest = cursor
        while (latest.version < cursor.version + CHANGES) latest = poll(latest).cursor
        val late = poll(cursor)
        assertFalse(late.complete)
    }

    @Test
    fun `a project that cannot be watched fails its polls at once, and is tried again later`() {
        watches.close()
        watches = watchesOf(WatchSettings(holdMs = SHORT_HOLD_MS, retryFailedMs = 500))
        // Stands for a watch the system refuses (more directories than inotify allows): the root is not a directory.
        val notWatchable = root.resolve("later")
        Files.writeString(notWatchable, "")
        val poll = { runCatching { runBlocking { watches.changes(notWatchable.toString(), DiskCursor.START) } } }
        assertTrue(poll().isFailure)
        // Still failing, without trying again: the directory it now is would be watched otherwise.
        Files.delete(notWatchable)
        notWatchable.createDirectories()
        assertTrue(poll().isFailure)
        Thread.sleep(600)
        assertTrue(poll().isSuccess)
    }
}
