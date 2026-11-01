package org.example.kotlineditor.lsp.documents

import org.example.kotlineditor.model.TextChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun textOf(vararg lines: String) = lines.joinToString("\n")

class OpenDocumentsTest {
    private val documents = OpenDocuments()
    private val events = mutableListOf<String>()

    private fun open(session: String = "s1", path: String = "/p/a.php", version: Int = 1, text: String) =
        documents.open(session, path, version, text, { "php" to "/p" }) { document, reopened ->
            events += "${if (reopened) "reopen" else "open"} ${document.path} ${document.lspVersion}"
        }

    private fun change(version: Int, vararg changes: TextChange, session: String = "s1") =
        documents.change(session, "/p/a.php", version, changes.toList()) { events += "change ${it.lspVersion}" }

    @Test
    fun `incremental edits apply in order`() {
        val document = assertNotNull(open(text = textOf("<?php", "echo 1;", "")))
        change(2, TextChange(1, 5, 1, 6, "42"), TextChange(2, 0, 2, 0, "foo();\nbar();"))
        assertEquals(textOf("<?php", "echo 42;", "foo();", "bar();"), document.text())
        change(3, TextChange(1, 0, 3, 0, ""))
        assertEquals(textOf("<?php", "bar();"), document.text())

        assertEquals(listOf("open /p/a.php 1", "change 2", "change 3"), events)
    }

    @Test
    fun `columns count UTF-16 units, an emoji counts two`() {
        val document = assertNotNull(open(text = "\$s = '😀';"))
        // The emoji is columns 6 and 7: the quote after it is column 8.
        change(2, TextChange(0, 8, 0, 9, "!'"))
        assertEquals("\$s = '😀!';", document.text())
    }

    @Test
    fun `a change for another version is refused and leaves the text alone`() {
        val document = assertNotNull(open(text = "abc"))
        assertFailsWith<OutOfSyncException> { change(3, TextChange(0, 0, 0, 0, "x")) }
        assertFailsWith<OutOfSyncException> { change(2, TextChange(0, 0, 0, 0, "x"), TextChange(5, 0, 5, 0, "y")) }
        assertEquals("abc", document.text())
        change(2, TextChange(0, 3, 0, 3, "d"))
        assertEquals("abcd", document.text())
    }

    @Test
    fun `the last session that opened a file owns it`() {
        open(text = "one")
        val document = assertNotNull(open(session = "s2", version = 7, text = "two"))
        assertFailsWith<OutOfSyncException> { change(2, TextChange(0, 0, 0, 0, "x")) }
        change(8, TextChange(0, 0, 0, 0, "x"), session = "s2")
        assertEquals("xtwo", document.text())
        assertEquals("reopen /p/a.php 2", events[1])
    }

    @Test
    fun `a file is closed once every session closed it`() {
        open(text = "one")
        open(session = "s2", text = "one")
        val closed = mutableListOf<String>()
        documents.close("s1", "/p/a.php") { closed += it.path }
        assertTrue(closed.isEmpty())
        documents.release("s2") { closed += it.path }
        assertEquals(listOf("/p/a.php"), closed)
        assertNull(documents.get("/p/a.php"))
    }

    @Test
    fun `a file no language server handles is not kept`() {
        val kept = documents.open("s1", "/p/readme.md", 1, "# hi", { null }) { _, _ -> events += "open" }
        assertNull(kept)
        assertTrue(events.isEmpty())
    }
}
