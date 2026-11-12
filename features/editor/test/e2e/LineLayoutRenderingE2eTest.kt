package org.example.kotlineditor.e2e

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.ui.IdeTheme
import org.example.kotlineditor.ui.LineLayouter
import org.example.kotlineditor.ui.LocalCodeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor lays out its lines itself, in paragraphs it closes ([LineLayouter]), rather than through Compose,
 * whose layouts are freed by the garbage collector. What it draws must be what Compose draws: the same font, size,
 * colors, styles and position, to the pixel.
 */
@OptIn(ExperimentalTestApi::class)
class LineLayoutRenderingE2eTest {
    private class Fonts(val style: TextStyle, val density: Density, val resolver: FontFamily.Resolver)

    @Composable
    private fun fonts() = Fonts(LocalCodeStyle.current, LocalDensity.current, LocalFontFamilyResolver.current)

    private fun image(fonts: Fonts, draw: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val size = Size(WIDTH.toFloat(), HEIGHT.toFloat())
        CanvasDrawScope().draw(fonts.density, LayoutDirection.Ltr, Canvas(bitmap), size) {
            drawRect(Color.Black)
            draw()
        }
        return bitmap
    }

    private fun assertSamePixels(expected: ImageBitmap, actual: ImageBitmap, what: String) {
        val expectedPixels = expected.toPixelMap()
        val actualPixels = actual.toPixelMap()
        var drawn = 0
        var different = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if (expectedPixels[x, y] != Color.Black) drawn++
                if (expectedPixels[x, y] != actualPixels[x, y]) different++
            }
        }
        assertTrue(drawn > MIN_DRAWN_PIXELS, "$what: Compose drew $drawn pixels")
        assertEquals(0, different, "$what: pixels that differ from what Compose draws, of $drawn drawn")
    }

    private fun styledLine(): AnnotatedString =
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFCF8E6D))) { append("val ") }
            append("total = ")
            withStyle(SpanStyle(color = Color(0xFF6AAB73))) { append("\"text é ü 日本\"") }
            append("\t+ ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF7A7E85))) { append("compute") }
            val link = SpanStyle(textDecoration = TextDecoration.Underline, color = Color(0xFF56A8F5))
            withStyle(link) { append("(link)") }
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(" old() ") }
            withStyle(SpanStyle(background = Color(0xFF393B40), color = Color(0xFF868A91))) { append(" hint: Int ") }
            append("!= -> // fi")
        }

    @Test
    fun `a line of code is drawn as Compose draws it, to the pixel`() =
        runComposeUiTest {
            var found: Fonts? = null
            setContent { IdeTheme { found = fonts() } }
            waitForIdle()
            val fonts = checkNotNull(found)
            val measurer = TextMeasurer(fonts.resolver, fonts.density, LayoutDirection.Ltr, cacheSize = 0)
            LineLayouter(fonts.style, fonts.density, fonts.resolver).use { layouter ->
                val line = styledLine()
                val topLeft = Offset(4f, 3f)
                val byCompose = measurer.measure(line, fonts.style, softWrap = false, maxLines = 1)
                val byEditor = layouter.layout(line)
                assertEquals(byCompose.size.height.toFloat(), byEditor.height, "height")
                assertEquals(byCompose.size.width.toFloat(), byEditor.width, 1f, "width")
                assertSamePixels(
                    image(fonts) { drawText(byCompose, topLeft = topLeft) },
                    image(fonts) { byEditor.draw(this, topLeft) },
                    "a line with colors, italics, decorations, a background and a tab",
                )
                // A line number, in a color that is not the one of the style; a blame, in a smaller font.
                val numberByCompose = measurer.measure("1234", fonts.style)
                assertSamePixels(
                    image(fonts) { drawText(numberByCompose, color = Color.Yellow, topLeft = topLeft) },
                    image(fonts) { layouter.layout("1234", Color.Yellow).draw(this, topLeft) },
                    "a line number",
                )
                val small = fonts.style.copy(fontSize = 11.sp, color = Color.Gray)
                LineLayouter(small, fonts.density, fonts.resolver).use { blames ->
                    assertSamePixels(
                        image(fonts) { drawText(measurer.measure("someone 3 days ago", small), topLeft = topLeft) },
                        image(fonts) { blames.layout("someone 3 days ago").draw(this, topLeft) },
                        "a blame annotation",
                    )
                }
            }
        }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 40
        const val MIN_DRAWN_PIXELS = 100
    }
}
