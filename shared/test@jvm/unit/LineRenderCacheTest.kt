package org.example.kotlineditor.unit

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.ui.LineRenderCache
import org.example.kotlineditor.ui.LineSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LineRenderCacheTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    private fun source(index: Int, text: String, startsInComment: Boolean = false, placeholder: String? = null) =
        LineSource(index, text, Language.Kotlin, startsInComment, false, placeholder != null, placeholder)

    @Test
    fun `lines with the same content share one layout, whatever their index`() {
        val cache = LineRenderCache(measurer, TextStyle())
        val first = cache.line(source(3, "val x = 1"))
        assertSame(first, cache.line(source(3, "val x = 1")))
        assertSame(first, cache.line(source(40, "val x = 1")))
        assertEquals(1, cache.size)
    }

    @Test
    fun `the comment state and the fold placeholder are part of the content`() {
        val cache = LineRenderCache(measurer, TextStyle())
        val plain = cache.line(source(0, "val x = 1"))
        assertNotSame(plain, cache.line(source(0, "val x = 1", startsInComment = true)))
        assertNotSame(plain, cache.line(source(0, "val x = 1", placeholder = "...}")))
        assertEquals(3, cache.size)
    }

    @Test
    fun `the least recently drawn lines are dropped first`() {
        val cache = LineRenderCache(measurer, TextStyle(), lineCapacity = 2)
        val a = cache.line(source(0, "a"))
        cache.line(source(1, "b"))
        cache.line(source(0, "a")) // a is now the most recent
        cache.line(source(2, "c")) // drops b
        assertSame(a, cache.line(source(0, "a")))
        assertEquals(2, cache.size)
    }
}
