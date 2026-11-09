package org.example.kotlineditor.unit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.syntax.SyntaxColor
import org.example.kotlineditor.ui.DarkPalette
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.LightPalette
import org.example.kotlineditor.ui.ThemeChoice
import org.example.kotlineditor.ui.badgeStyle
import org.example.kotlineditor.ui.fileBadge
import org.example.kotlineditor.ui.gitColor
import org.example.kotlineditor.ui.plural
import org.example.kotlineditor.ui.syntaxStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a saved theme is read by name, an unknown or missing one is dark`() {
        assertEquals(ThemeChoice.Light, ThemeChoice.fromSaved("Light"))
        assertEquals(ThemeChoice.System, ThemeChoice.fromSaved("System"))
        assertEquals(ThemeChoice.Dark, ThemeChoice.fromSaved("light"))
        assertEquals(ThemeChoice.Dark, ThemeChoice.fromSaved(null))
    }

    @Test
    fun `the system theme follows the operating system, the others do not`() {
        assertEquals(DarkPalette, ThemeChoice.System.paletteFor(systemDark = true))
        assertEquals(LightPalette, ThemeChoice.System.paletteFor(systemDark = false))
        assertEquals(DarkPalette, ThemeChoice.Dark.paletteFor(systemDark = false))
        assertEquals(LightPalette, ThemeChoice.Light.paletteFor(systemDark = true))
    }

    @Test
    fun `the light palette is light and readable, the dark one is dark`() {
        assertTrue(DarkPalette.dark)
        assertFalse(LightPalette.dark)
        assertTrue(LightPalette.editor.luminance() > 0.9f)
        assertTrue(DarkPalette.editor.luminance() < 0.1f)
        assertTrue(contrast(LightPalette.code, LightPalette.editor) >= 7f)
        assertTrue(contrast(LightPalette.text, LightPalette.panel) >= 7f)
        assertTrue(contrast(LightPalette.comment, LightPalette.editor) >= 3f)
        assertEquals(DarkPalette.bracketDepths.size, LightPalette.bracketDepths.size)
    }

    // WCAG contrast ratio of two colors.
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance() + 0.05f
        val second = b.luminance() + 0.05f
        return maxOf(first, second) / minOf(first, second)
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
