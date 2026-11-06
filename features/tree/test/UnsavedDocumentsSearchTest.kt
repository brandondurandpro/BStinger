package org.example.kotlineditor.unit

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.SearchOptions
import org.example.kotlineditor.state.SearchHit
import org.example.kotlineditor.state.UnsavedDocument
import org.example.kotlineditor.state.UnsavedDocumentsSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FILE = FileEntry("/project/a.txt", "a.txt", false)
private val OTHER = FileEntry("/project/sub/b.md", "b.md", false)

/** A fake hit of the disk search, as its caller could have built it (its [path] only matters here). */
private fun hit(
    path: String,
    line: Int,
    column: Int = 0,
    lineText: String = "",
    entry: FileEntry = FILE,
) = SearchHit(entry, path, line, column, lineText)

/** The hits of the documents modified in memory, and their replacement of the disk hits of the same files. */
class UnsavedDocumentsSearchTest {
    private val relativePath: (FileEntry) -> String = { entry -> if (entry === FILE) "a.txt" else "sub/b.md" }
    private val search = UnsavedDocumentsSearch(relativePath)

    private fun document(lines: List<String>, entry: FileEntry = FILE) = UnsavedDocument(entry, lines)

    @Test
    fun `finds occurrences with their column on one line at most`() {
        val hits = search.hits("word", listOf(document(listOf("word x word", "no match"))))
        assertEquals(1, hits.size)
        val one = hits.single()
        assertEquals(0, one.line)
        assertEquals(0, one.column)
        assertEquals("word x word", one.lineText)
    }

    @Test
    fun `an empty query finds nothing`() {
        assertTrue(search.hits("", listOf(document(listOf("anything")))).isEmpty())
    }

    @Test
    fun `letter case is ignored or not, as the option says`() {
        val document = document(listOf("wor", "Hello hello", "wOr"))
        val insensitive = search.hits("hello", listOf(document))
        // One hit per line, the first match of the line: the insensitive one at "Hello", the exact one later.
        assertEquals(1, insensitive.size)
        assertEquals(1, insensitive.single().line)
        assertEquals(0, insensitive.single().column)

        val options = SearchOptions(ignoreCase = false)
        val exact = search.hits("hello", listOf(document), options)
        assertEquals(1, exact.size)
        assertEquals(1, exact.single().line)
        assertEquals(6, exact.single().column)
    }

    @Test
    fun `whole word matches skip matches inside words`() {
        val document = document(listOf("everyword is a word", "another WORD"))
        val options = SearchOptions(wholeWord = true, ignoreCase = false)
        val hits = search.hits("word", listOf(document), options)
        assertEquals(1, hits.size)
        assertEquals(0, hits.single().line)
        assertEquals(15, hits.single().column)
    }

    @Test
    fun `a regular expression matches or finds nothing when it is broken`() {
        val document = document(listOf("n123", "n45 x"))
        val hits = search.hits("""n\d""", listOf(document), SearchOptions(regex = true, ignoreCase = false))
        // One hit per line, at the column of the first match of the line.
        assertEquals(listOf(0 to 0, 1 to 0), hits.map { it.line to it.column })
        assertTrue(search.hits("""([""", listOf(document), SearchOptions(regex = true)).isEmpty())
    }

    @Test
    fun `hits are sorted by path then line`() {
        val documents =
            listOf(
                document(listOf("x y", "y"), OTHER),
                document(listOf("x y", "y"), FILE),
            )
        val hits = search.hits("y", documents)
        assertEquals(
            listOf(
                hit("a.txt", 0, column = 2, lineText = "x y"),
                hit("a.txt", 1, lineText = "y"),
                hit("sub/b.md", 0, column = 2, lineText = "x y", entry = OTHER),
                hit("sub/b.md", 1, lineText = "y", entry = OTHER),
            ),
            hits,
        )
    }

    @Test
    fun `merge adds the unsaved hits and the whole list stays sorted by path then line`() {
        // Dropping the disk hits of the modified files is the popup's job (they must go even when the modified
        // text does not match); merge itself only unites and sorts the two halves.
        val unsaved =
            listOf(
                hit("a.txt", 3),
                hit("sub/b.md", 0),
            )
        val disk =
            listOf(
                hit("a.txt", 0),
                hit("a.txt", 7),
                hit("src/c.kt", 0, column = 5),
            )
        val merged = search.merge(disk, unsaved)
        assertEquals(
            listOf(
                hit("a.txt", 0),
                hit("a.txt", 3),
                hit("a.txt", 7),
                hit("src/c.kt", 0, column = 5),
                hit("sub/b.md", 0),
            ),
            merged,
        )
    }
}
