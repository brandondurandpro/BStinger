package org.example.kotlineditor.unit

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.CommitDiffTarget
import org.example.kotlineditor.state.tabKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Identity of the editor tabs: one tab per file and kind, whatever separators its path was built with. */
class TabKeyTest {
    private fun entry(path: String) =
        FileEntry(path = path, name = path.substringAfterLast('/').substringAfterLast('\\'), isDirectory = false)

    @Test
    fun `a Windows path from the tree and the same path rebuilt from Git open one tab`() {
        val fromTree = entry("""C:\work\project\src\App.kt""")
        val fromGit = entry("""C:\work\project/src/App.kt""")
        assertEquals(tabKey(fromTree, isDiff = false, commit = null), tabKey(fromGit, isDiff = false, commit = null))
        assertEquals(tabKey(fromTree, isDiff = true, commit = null), tabKey(fromGit, isDiff = true, commit = null))
    }

    @Test
    fun `the text, the uncommitted diff and the diff of each commit are different tabs`() {
        val file = entry("/work/project/src/App.kt")
        val first = CommitDiffTarget("aaaaaaaaaa", "aaaaaaaa")
        val second = CommitDiffTarget("bbbbbbbbbb", "bbbbbbbb")
        val keys =
            listOf(
                tabKey(file, isDiff = false, commit = null),
                tabKey(file, isDiff = true, commit = null),
                tabKey(file, isDiff = true, commit = first),
                tabKey(file, isDiff = true, commit = second),
            )
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }
}
