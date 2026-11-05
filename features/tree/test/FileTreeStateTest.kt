package org.example.kotlineditor.unit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.state.FileTreeState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ROOT = FileEntry("/p", "p", isDirectory = true)
private val SUB = FileEntry("/p/sub", "sub", isDirectory = true)
private val INNER = FileEntry("/p/sub/inner", "inner", isDirectory = true)
private val OLD = FileEntry("/p/old.txt", "old.txt", isDirectory = false)
private val NEW = FileEntry("/p/new.txt", "new.txt", isDirectory = false)
private val SUB_OLD = FileEntry("/p/sub/old.txt", "old.txt", isDirectory = false)
private val SUB_NEW = FileEntry("/p/sub/new.txt", "new.txt", isDirectory = false)
private val DEEP = FileEntry("/p/sub/inner/deep.txt", "deep.txt", isDirectory = false)

/** Listings answer in the order the test chooses: what the tree keeps must not depend on it. */
class FileTreeStateTest {
    private val fileSystem = ManualFileSystem()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private fun treeOf(rootChildren: List<FileEntry>): FileTreeState {
        val tree = FileTreeState(ROOT, fileSystem, scope)
        fileSystem.answer(ROOT, rootChildren)
        return tree
    }

    private fun FileTreeState.shownPaths(): List<String> = rows.map { it.entry.path }

    @Test
    fun `an older listing answering last does not replace a newer one`() {
        val tree = treeOf(listOf(OLD))
        tree.reload(ROOT.path)
        tree.reload(ROOT.path)
        fileSystem.answerLast(ROOT, listOf(OLD, NEW))
        fileSystem.answer(ROOT, listOf(OLD))
        assertTrue(NEW.path in tree.shownPaths())
    }

    @Test
    fun `listings answering in order keep the last one`() {
        val tree = treeOf(listOf(OLD))
        tree.reload(ROOT.path)
        tree.reload(ROOT.path)
        fileSystem.answer(ROOT, listOf(OLD))
        fileSystem.answer(ROOT, listOf(OLD, NEW))
        assertTrue(NEW.path in tree.shownPaths())
    }

    @Test
    fun `a listing copied from the cache by a reveal does not replace the reload that answered since`() {
        val tree = treeOf(listOf(SUB, OLD))
        tree.reload(ROOT.path)
        tree.reveal(DEEP.path)
        fileSystem.answer(ROOT, listOf(SUB, OLD, NEW))
        fileSystem.answer(SUB, listOf(INNER))
        fileSystem.answer(INNER, listOf(DEEP))
        assertEquals(listOf(ROOT, SUB, INNER, DEEP, OLD, NEW).map { it.path }, tree.shownPaths())
    }

    @Test
    fun `a directory forgotten by a refresh during a reveal is listed again, not filled from before`() {
        val tree = treeOf(listOf(SUB))
        tree.expand(SUB)
        fileSystem.answer(SUB, listOf(INNER, SUB_OLD))
        tree.collapse(SUB)
        tree.reveal(DEEP.path)
        tree.refresh()
        fileSystem.answer(ROOT, listOf(SUB))
        fileSystem.answer(INNER, listOf(DEEP))
        fileSystem.answer(SUB, listOf(INNER, SUB_OLD, SUB_NEW))
        assertTrue(SUB_NEW.path in tree.shownPaths())
        assertTrue(DEEP.path in tree.shownPaths())
    }

    @Test
    fun `a reveal expands the directories down to the file and selects it`() {
        val tree = treeOf(listOf(SUB))
        tree.reveal(DEEP.path)
        fileSystem.answer(SUB, listOf(INNER))
        fileSystem.answer(INNER, listOf(DEEP))
        assertEquals(listOf(ROOT, SUB, INNER, DEEP).map { it.path }, tree.shownPaths())
        assertEquals(DEEP.path, tree.selectedPath)
    }
}

/** Keeps every listing asked until the test answers it. */
private class ManualFileSystem : ProjectFileSystem {
    private val asked = mutableListOf<Pair<FileEntry, CompletableDeferred<List<FileEntry>>>>()

    override suspend fun list(directory: FileEntry): List<FileEntry> {
        val answer = CompletableDeferred<List<FileEntry>>()
        asked += directory to answer
        return answer.await()
    }

    /** Answers the oldest listing of [directory] still waiting. */
    fun answer(directory: FileEntry, entries: List<FileEntry>) = answer(directory, entries, oldest = true)

    /** Answers the newest listing of [directory] still waiting. */
    fun answerLast(directory: FileEntry, entries: List<FileEntry>) = answer(directory, entries, oldest = false)

    private fun answer(directory: FileEntry, entries: List<FileEntry>, oldest: Boolean) {
        val waiting = asked.filter { it.first.path == directory.path }
        val chosen = if (oldest) waiting.firstOrNull() else waiting.lastOrNull()
        checkNotNull(chosen) { "No listing of ${directory.path} is waiting" }
        asked -= chosen
        chosen.second.complete(entries)
    }

    override suspend fun resolveDirectory(path: String): FileEntry? = unused()

    override suspend fun read(file: FileEntry): FileContent = unused()

    override suspend fun fileAt(path: String): FileEntry? = unused()

    override suspend fun write(file: FileEntry, text: String): Unit = unused()

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry = unused()

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry = unused()

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry = unused()

    override suspend fun delete(entry: FileEntry): Unit = unused()

    private fun unused(): Nothing = error("Not used by the tree")
}
