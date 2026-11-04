package org.example.kotlineditor.unit

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.syntax.SyntaxColor
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.badgeStyle
import org.example.kotlineditor.ui.fileBadge
import org.example.kotlineditor.ui.gitColor
import org.example.kotlineditor.ui.plural
import org.example.kotlineditor.ui.syntaxStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThemeTest {
    @Test
    fun `a syntax color has the same style by ordinal as by value`() {
        SyntaxColor.entries.forEach { color ->
            assertEquals(syntaxStyle(color), syntaxStyle(color.ordinal), "color $color")
        }
        assertEquals(FontStyle.Italic, syntaxStyle(SyntaxColor.Comment).fontStyle)
        assertEquals(TextDecoration.Underline, syntaxStyle(SyntaxColor.Link).textDecoration)
    }

    @Test
    fun `a renamed file has the color of a modified one`() {
        assertEquals(gitColor(GitFileStatus.Modified), gitColor(GitFileStatus.Renamed))
        assertEquals(IdeColors.GitAdded, gitColor(GitFileStatus.Added))
    }

    @Test
    fun `a file badge comes from its extension, whatever its case`() {
        assertEquals("K", fileBadge("Main.kt")?.letter)
        assertEquals("K", fileBadge("build.gradle.KTS")?.letter)
        assertEquals("JS", fileBadge("app.min.js")?.letter)
        assertEquals("Y", fileBadge("ci.yml")?.letter)
    }

    @Test
    fun `files of unknown type, or without extension, have no badge`() {
        assertNull(fileBadge("Makefile"))
        assertNull(fileBadge("notes.txt"))
        assertNull(fileBadge("trailing."))
        assertNull(fileBadge(".gitignore"))
    }

    @Test
    fun `badges of several letters are written smaller`() {
        val single = assertNotNull(fileBadge("Main.kt"))
        val double = assertNotNull(fileBadge("index.ts"))
        assertEquals(9.sp, badgeStyle(single).fontSize)
        assertEquals(7.sp, badgeStyle(double).fontSize)
    }

    @Test
    fun `plural picks the singular for one only`() {
        assertEquals("1 file", plural(1, "file", "files"))
        assertEquals("0 files", plural(0, "file", "files"))
        assertEquals("2 files", plural(2, "file", "files"))
    }
}
