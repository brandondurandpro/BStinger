package org.example.kotlineditor.unit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.server.LocalFileSystem
import org.example.kotlineditor.state.FolderPickerState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderPickerStateTest {
    private val root: Path = Files.createTempDirectory("folder-picker")

    init {
        root.resolve("a/b").createDirectories()
        root.resolve("a/file.txt").writeText("not a folder")
        root.resolve("only-file.txt").writeText("x")
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `starts in the given folder, enters subfolders and comes back`() {
        if (isWindows()) return // the picker browses POSIX remote paths
        runBlocking {
            val state = picker(CoroutineScope(coroutineContext))
            awaitLoaded(state)
            assertEquals(root.toString(), state.current?.path)
            assertEquals(listOf("a"), state.entries.map { it.name }, "only directories are listed")

            val a = state.entries.single()
            state.enter(a)
            awaitPath(state, a.path)
            assertEquals(listOf("b"), state.entries.map { it.name })

            state.goUp()
            awaitPath(state, root.toString())
            state.goHome()
            awaitPath(state, root.toString())
            assertEquals(root.toString(), state.choose())
        }
    }

    @Test
    fun `the tilde is the SSH home and a bad path is reported without losing the folder`() {
        if (isWindows()) return
        runBlocking {
            val state = picker(CoroutineScope(coroutineContext))
            awaitLoaded(state)

            state.editPath("~/a")
            state.submitPath()
            awaitPath(state, root.resolve("a").toString())
            assertNull(state.error)

            val kept = state.current
            state.editPath("/does/not/exist")
            state.submitPath()
            awaitError(state)
            assertTrue(state.error.orEmpty().contains("not found", ignoreCase = true))
            assertEquals(kept, state.current, "a failed navigation keeps the folder that was open")

            state.editPath("~")
            state.submitPath()
            awaitPath(state, root.toString())
            assertNull(state.error)
        }
    }

    @Test
    fun `typing a path checks it after a debounce, without pressing Enter`() {
        if (isWindows()) return
        runBlocking {
            val state = picker(CoroutineScope(coroutineContext))
            awaitLoaded(state)

            state.editPath("~/a")
            assertTrue(state.checking, "the typed path is being checked")
            assertFalse(state.canChoose, "Open waits for the check")
            assertEquals(root.toString(), state.current?.path, "the current folder stays until the check completes")
            awaitPath(state, root.resolve("a").toString())
            assertFalse(state.checking)
            assertTrue(state.canChoose)

            // Typing must not be rewritten under the caret; a trailing slash still selects the folder.
            state.editPath("~/a/")
            awaitPath(state, root.resolve("a").toString())
            assertEquals("~/a/", state.path)
            assertTrue(state.canChoose)

            // Two quick edits: only the last path may win.
            state.editPath("~/a")
            state.editPath("~")
            awaitPath(state, root.toString())
            assertEquals(root.toString(), state.current?.path)
        }
    }

    @Test
    fun `a file instead of a folder is reported and cannot be opened`() {
        if (isWindows()) return
        runBlocking {
            val file = root.resolve("only-file.txt")
            val state = picker(CoroutineScope(coroutineContext), file.toString())
            awaitError(state)
            assertTrue(state.error.orEmpty().contains("not found", ignoreCase = true))
            assertNull(state.current)
            assertNull(state.choose())
        }
    }

    private fun picker(scope: CoroutineScope, start: String = root.toString()): FolderPickerState =
        FolderPickerState(LocalFileSystem(), scope, start, root.toString())

    private suspend fun awaitLoaded(state: FolderPickerState) {
        withTimeout(5_000) {
            while (state.current == null && state.error == null) delay(5)
            while (state.loading || state.checking) delay(5)
        }
    }

    private suspend fun awaitPath(state: FolderPickerState, path: String) {
        withTimeout(5_000) {
            while (state.current?.path != path || state.loading || state.checking) delay(5)
        }
    }

    private suspend fun awaitError(state: FolderPickerState) {
        withTimeout(5_000) {
            while (state.error == null || state.loading || state.checking) delay(5)
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
