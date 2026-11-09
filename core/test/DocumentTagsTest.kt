package org.example.kotlineditor.unit

import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.closesTags
import org.example.kotlineditor.state.typeChar
import org.example.kotlineditor.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentTagsTest {
    // A document holding [before] and [after] around the caret, where `>` is typed with tags closing.
    private fun typeEnd(before: String, after: String = "", closeTags: Boolean = true): Document {
        val text = before + after
        val document = Document(text.split('\n'), Language.Plain)
        val lines = before.split('\n')
        document.moveTo(TextPos(lines.size - 1, lines.last().length))
        document.typeChar('>', closeTags)
        return document
    }

    @Test
    fun `typing the end of an opening tag adds its closing tag after the caret`() {
        val document = typeEnd("<Navbar")
        assertEquals("<Navbar></Navbar>", document.text())
        assertEquals(TextPos(0, "<Navbar>".length), document.caret)
    }

    @Test
    fun `a tag written over several lines is closed too`() {
        val document = typeEnd("<Navbar\n  class=\"a b\"\n  :title=\"t\"\n")
        assertEquals("<Navbar\n  class=\"a b\"\n  :title=\"t\"\n></Navbar>", document.text())
        assertEquals(TextPos(3, 1), document.caret)
    }

    @Test
    fun `a multi-line self-closed tag and text after a finished tag get nothing`() {
        assertEquals("<Navbar\n  a=\"1\"\n/>", typeEnd("<Navbar\n  a=\"1\"\n/").text())
        assertEquals("<p>a</p>\nb >", typeEnd("<p>a</p>\nb ").text())
    }

    @Test
    fun `a multi-line tag inside a script body is not closed`() {
        val document = typeEnd("<script>\nconst a = <div\n  b=\"1\"\n")
        assertEquals("<script>\nconst a = <div\n  b=\"1\"\n>", document.text())
    }

    @Test
    fun `attributes, bindings and events do not prevent it`() {
        val document = typeEnd("""<div class="a b" :id="x > 1" @click="go()" v-if="ok" data-x""")
        assertTrue(document.text().endsWith("></div>"), document.text())
        assertEquals(1, Regex("</div>").findAll(document.text()).count())
    }

    @Test
    fun `a self-closed tag, a void element and a closing tag get nothing`() {
        assertEquals("<Navbar />", typeEnd("<Navbar /").text())
        assertEquals("<br>", typeEnd("<br").text())
        assertEquals("""<img src="a.png">""", typeEnd("""<img src="a.png"""").text())
        assertEquals("</div>", typeEnd("</div").text())
    }

    @Test
    fun `arrows, comparisons and text are not tags`() {
        assertEquals("a => b", typeEnd("a =", " b").text())
        assertEquals("if (a < b && c>", typeEnd("if (a < b && c").text())
        assertEquals("x <b && c>", typeEnd("x <b && c").text())
    }

    @Test
    fun `nothing is added inside a script or style body, but the tags of the blocks are closed`() {
        val body = listOf("""<script setup lang="ts">""", "const a = 1", "if (b <c").joinToString("\n")
        assertEquals("$body>", typeEnd(body).text())
        val opening = typeEnd("""<script setup lang="ts"""")
        assertEquals("""<script setup lang="ts"></script>""", opening.text())
    }

    @Test
    fun `an already closed tag is not closed twice, and other files are untouched`() {
        assertEquals("<Navbar></Navbar>", typeEnd("<Navbar", "</Navbar>").text())
        assertEquals("<Navbar>", typeEnd("<Navbar", closeTags = false).text())
    }

    @Test
    fun `only vue and html files close their tags`() {
        assertTrue(closesTags("src/App.vue"))
        assertTrue(closesTags("index.HTML"))
        assertTrue(closesTags("a/b.htm"))
        assertFalse(closesTags("src/App.tsx"))
        assertFalse(closesTags("src/main.ts"))
        assertFalse(closesTags("Makefile"))
    }
}
