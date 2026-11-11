package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputLinksTest {
    @Test
    fun `a Kotlin compiler error links to its file at its line and column`() {
        val line = "e: file:///home/me/p/src/App.kt:12:5 Unresolved reference: foo"
        val link = findOutputLinks(line).single()
        assertEquals("/home/me/p/src/App.kt", link.path)
        assertEquals(12, link.line)
        assertEquals(5, link.column)
        assertEquals("file:///home/me/p/src/App.kt:12:5", line.substring(link.start, link.end))
    }

    @Test
    fun `a stack frame links to its file name and line`() {
        val line = "    at demo.AppTest.greets(AppTest.kt:7)"
        val link = findOutputLinks(line).single()
        assertEquals("AppTest.kt", link.path)
        assertEquals(7, link.line)
        assertEquals(1, link.column)
        assertEquals("AppTest.kt:7", line.substring(link.start, link.end))
    }

    @Test
    fun `a Windows short name with a tilde stays in the path`() {
        val line = "e: file:///C:/Users/RUNNER~1/AppData/p/App.kt:8:5 Unresolved reference: x"
        val link = findOutputLinks(line).single()
        assertEquals("C:/Users/RUNNER~1/AppData/p/App.kt", link.path)
        assertEquals(8, link.line)
        assertEquals(5, link.column)
    }

    @Test
    fun `a relative path with a line links to it`() {
        val link = findOutputLinks("src/main/kotlin/App.kt:3: error").single()
        assertEquals("src/main/kotlin/App.kt", link.path)
        assertEquals(3, link.line)
    }

    @Test
    fun `a Windows path is kept whole`() {
        val link = findOutputLinks("""C:\work\p\App.kt:9:2 oops""").single()
        assertEquals("""C:\work\p\App.kt""", link.path)
        assertEquals(9, link.line)
        assertEquals(2, link.column)
    }

    @Test
    fun `several places on a line are found left to right`() {
        val links = findOutputLinks("A.kt:1 calls B.java:22")
        assertEquals(listOf("A.kt", "B.java"), links.map { it.path })
        assertEquals(listOf(1, 22), links.map { it.line })
    }

    @Test
    fun `a web address with a port is not a place in a file`() {
        assertTrue(findOutputLinks("see src/index.html:8080 not a position").isEmpty())
        assertTrue(findOutputLinks("Server started at localhost:8080").isEmpty())
    }

    @Test
    fun `a line without a place has no link`() {
        assertTrue(findOutputLinks("BUILD SUCCESSFUL in 3s").isEmpty())
        assertTrue(findOutputLinks("").isEmpty())
    }

    @Test
    fun `colors are removed from a line`() {
        assertEquals("e: bad", stripAnsi("\u001B[31me: bad\u001B[0m"))
        assertEquals("plain", stripAnsi("plain"))
    }

    @Test
    fun `a web address is a link, without the punctuation that ends the sentence`() {
        val line = "  Local:   http://localhost:5173/, ready"
        val link = findOutputLinks(line).single()
        assertEquals("http://localhost:5173/", link.url)
        assertEquals("http://localhost:5173/", line.substring(link.start, link.end))
    }

    @Test
    fun `a web address with a file name in it is one link`() {
        val links = findOutputLinks("see https://example.com/src/App.kt:12 and A.kt:3")
        assertEquals(listOf("https://example.com/src/App.kt:12", null), links.map { it.url })
    }
}
