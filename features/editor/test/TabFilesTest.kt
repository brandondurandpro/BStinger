package org.example.kotlineditor.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.example.kotlineditor.model.FileChangedException
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileVersion
import org.example.kotlineditor.model.OutcomeUnknownException
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.syntax.Language
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SETTLE_YIELDS = 50

// A server whose writes wait for [release], fail for the files of [conflicting] with a file-changed check (nothing
// written), fail for those of [failing], never answer for those of [silent], and throw an outcome-unknown once for
// those of [unknownOnce] before writing. Every write is recorded with the version its caller expected.
private class GatedFileSystem : ProjectFileSystem {
    val written = ArrayList<Pair<String, String>>()
    val expectedVersions = ArrayList<FileVersion?>()
    val conflicting = HashSet<String>()
    val failing = HashSet<String>()
    val silent = HashSet<String>()
    val unknownOnce = HashSet<String>()
    var inFlight = 0
    var mostInFlight = 0
    private var gate = CompletableDeferred<Unit>()

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun write(file: FileEntry, text: String, expected: FileVersion?): FileVersion? {
        inFlight++
        mostInFlight = maxOf(mostInFlight, inFlight)
        expectedVersions += expected
        try {
            gate.await()
            if (file.name in silent) awaitCancellation()
            val blocked = outcomeOf(file)
            if (blocked != null) throw blocked
            written += file.name to text
        } finally {
            inFlight--
        }
        // What the disk now holds; only its being different from what else came would matter, and nothing here
        // does: the tests read the versions back, they do not hash the texts.
        return FileVersion(0L, text.length.toLong(), null, "written")
    }

    // How the fake server answers a write: a failure (whose case is spent, for the outcome-unknown one), or nothing.
    private fun outcomeOf(file: FileEntry): IOException? =
        when {
            file.name in unknownOnce -> {
                // Saw the write, got lost before answering: nothing says the file was changed.
                unknownOnce.remove(file.name)
                OutcomeUnknownException("fs/write")
            }

            file.name in conflicting -> {
                FileChangedException()
            }

            file.name in failing -> {
                IOException("the server is unreachable")
            }

            else -> {
                null
            }
        }

    override suspend fun resolveDirectory(path: String): FileEntry? = null

    override suspend fun list(directory: FileEntry): List<FileEntry> = emptyList()

    override suspend fun read(file: FileEntry): FileContent = FileContent.Error("not read in these tests")

    override suspend fun fileAt(path: String): FileEntry? = null

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry = throw IOException("unused")

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry = throw IOException("unused")

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry = throw IOException("unused")

    override suspend fun delete(entry: FileEntry) = Unit
}

/** How the editor saves its tabs: one save of a document at a time, and each tab whatever happens to the others. */
class TabFilesTest {
    private val fs = GatedFileSystem()

    private fun CoroutineScope.files() =
        TabFiles(
            fs,
            this,
            {},
            { emptyList() },
            { emptyList() },
            { _, _ -> emptyList() },
            { throw IOException("no merge") },
            null,
        )

    // A tab showing [name] with [text], typed but not saved.
    private fun modifiedTab(name: String, text: String = "first"): EditorTab {
        val tab = EditorTab(FileEntry("/project/$name", name, false), name, false, null)
        val document = Document(listOf(""), Language.fromFileName(name))
        document.replace(TextPos(0, 0), TextPos(0, 0), text)
        tab.status = TabStatus.Ready(document)
        return tab
    }

    private fun EditorTab.retype(text: String) {
        val document = checkNotNull(document)
        document.replace(TextPos(0, 0), TextPos(0, document.lines[0].length), text)
    }

    private suspend fun settle() = repeat(SETTLE_YIELDS) { yield() }

    @Test
    fun `a save asked while one runs waits for it, and the last text is what stays on disk`() =
        runBlocking {
            val files = files()
            val tab = modifiedTab("a.txt")
            files.save(tab)
            settle()
            tab.retype("second")
            files.save(tab)
            tab.retype("third")
            files.save(tab)
            settle()
            assertEquals(1, fs.inFlight, "Saves of one document in flight together")
            fs.release()
            settle()
            assertEquals(1, fs.mostInFlight)
            // The two saves asked meanwhile are one, with the text as it was when it started.
            assertEquals(listOf("a.txt" to "first", "a.txt" to "third"), fs.written)
            assertFalse(checkNotNull(tab.document).isModified)
        }

    @Test
    fun `two tabs of one document, one in each pane, are saved one after the other`() =
        runBlocking {
            val files = files()
            val left = modifiedTab("a.txt")
            val right = EditorTab(left.entry, "a.txt", false, null).also { it.status = left.status }
            files.save(left)
            settle()
            right.retype("second")
            files.save(right)
            settle()
            fs.release()
            settle()
            assertEquals(1, fs.mostInFlight)
            assertEquals("a.txt" to "second", fs.written.lastOrNull())
        }

    @Test
    fun `a tab that cannot be saved does not keep the others from being saved`() =
        runBlocking {
            val files = files()
            fs.failing += "a.txt"
            fs.release()
            val tabs = listOf(modifiedTab("a.txt"), modifiedTab("b.txt"), modifiedTab("c.txt"))
            runCatching { files.flush(tabs) }
            assertEquals(listOf("b.txt", "c.txt"), fs.written.map { it.first }.sorted())
        }

    @Test
    fun `flushing tells which files could not be saved and why, and gives up a save without answer`() =
        runBlocking {
            val files = files()
            fs.failing += "a.txt"
            fs.silent += "b.txt"
            fs.release()
            val tabs = listOf(modifiedTab("a.txt"), modifiedTab("b.txt"), modifiedTab("c.txt"))
            val unsaved = files.flush(tabs, timeoutMillis = 200)
            assertEquals(listOf("a.txt", "b.txt"), unsaved.map { it.entry.name })
            assertTrue("unreachable" in unsaved[0].reason)
            assertTrue("did not answer" in unsaved[1].reason)
            assertEquals(listOf("c.txt"), fs.written.map { it.first })
            assertTrue(checkNotNull(tabs[0].document).isModified)
            assertTrue(checkNotNull(tabs[1].document).isModified)
            assertFalse(checkNotNull(tabs[2].document).isModified)
        }

    @Test
    fun `flushing waits for the save already running, then saves what was typed since`() =
        runBlocking {
            val files = files()
            val tab = modifiedTab("a.txt")
            files.save(tab)
            settle()
            tab.retype("second")
            fs.release()
            val unsaved = files.flush(listOf(tab))
            assertEquals(emptyList(), unsaved)
            assertEquals(1, fs.mostInFlight)
            assertEquals("a.txt" to "second", fs.written.lastOrNull())
        }

    @Test
    fun `a save carries the version the document knows, and records the one the disk answered`() =
        runBlocking {
            val files = files()
            val tab = modifiedTab("a.txt")
            val known = FileVersion(1L, 2L, null, "read")
            checkNotNull(tab.document).diskVersion = known
            files.save(tab)
            settle()
            // Sent with the save, not after it: the server checks before writing.
            assertEquals(listOf<FileVersion?>(known), fs.expectedVersions)
            fs.release()
            settle()
            val written = assertNotNull(fs.written.singleOrNull { it.first == "a.txt" })
            assertEquals("first", written.second)
            val document = checkNotNull(tab.document)
            assertFalse(document.isModified)
            assertEquals("written", document.diskVersion?.sha256)
            assertFalse(document.diskConflict)
        }

    @Test
    fun `a save refused for a file another program wrote marks it in conflict and writes nothing`() =
        runBlocking {
            val files = files()
            fs.conflicting += "a.txt"
            fs.release()
            val tab = modifiedTab("a.txt")
            files.save(tab)
            settle()
            val document = checkNotNull(tab.document)
            assertTrue(document.diskConflict, "The conflict must be shown (the banner), not only counted")
            assertTrue(document.isModified)
            assertTrue(fs.written.isEmpty(), "Nothing was written: the disk keeps what another program wrote")
        }

    @Test
    fun `a save whose answer was unknown is replayed once, with the same expected version`() =
        runBlocking {
            val files = files()
            val tab = modifiedTab("a.txt")
            val known = FileVersion(3L, 4L, null, "read")
            checkNotNull(tab.document).diskVersion = known
            fs.unknownOnce += "a.txt"
            fs.release()
            files.save(tab)
            settle()
            // Two calls, the same expected version: if the first one landed, the replay's bytes are the disk's.
            assertEquals(listOf<FileVersion?>(known, known), fs.expectedVersions)
            assertEquals(listOf("a.txt" to "first"), fs.written)
            val document = checkNotNull(tab.document)
            assertFalse(document.isModified)
            assertEquals("written", document.diskVersion?.sha256)
            assertFalse(document.diskConflict)
        }
}
