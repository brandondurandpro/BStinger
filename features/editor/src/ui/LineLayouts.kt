package org.example.kotlineditor.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontFeature
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.paragraph.DecorationLineStyle
import org.jetbrains.skia.paragraph.DecorationStyle
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.HeightMode
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import org.jetbrains.skia.FontHinting as SkFontHinting
import org.jetbrains.skia.FontStyle as SkFontStyle
import org.jetbrains.skia.paragraph.TextStyle as SkTextStyle

/**
 * One line of text laid out, whose native memory (a paragraph of Skia: kilobytes for a line of code) is freed by
 * [close] rather than when the garbage collector finds it. Not drawn after it was closed.
 */
internal class LineLayout(private val paragraph: Paragraph) : AutoCloseable {
    val width: Float = paragraph.maxIntrinsicWidth
    val height: Float = paragraph.height

    /** Whether [close] was called. */
    var isClosed = false
        private set

    fun draw(scope: DrawScope, topLeft: Offset) {
        if (isClosed) return
        scope.drawIntoCanvas { paragraph.paint(it.nativeCanvas, topLeft.x, topLeft.y) }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        paragraph.close()
    }
}

/**
 * Lays out the lines of an editor that draws its rows itself, as Compose would lay out a text of [style] on one
 * line, but in paragraphs the editor owns.
 *
 * Compose frees the native memory of a text when its Java objects are collected. Those of the lines scrolled
 * through grow old in the cache of lines, where the young collections of a scroll do not find them: the memory of
 * the IDE grew by a gigabyte in a few screens of a large file. Here a line evicted from the cache is closed, and
 * the styles of the colors, a few per theme, are built once instead of for each line.
 */
@OptIn(ExperimentalTextApi::class)
internal class LineLayouter(
    private val style: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
) : AutoCloseable {
    private val typefaces = TypefaceFontProvider()
    private val fonts =
        FontCollection().apply {
            setDefaultFontManager(FontMgr.default)
            setAssetFontManager(typefaces)
        }
    private val registered = HashSet<String>()
    private val base = style.toSpanStyle()
    private val fontSize = with(density) { (style.fontSize.takeIf { it.isSpecified } ?: DEFAULT_FONT_SIZE).toPx() }
    private val styles = HashMap<SpanStyle, SkTextStyle>()

    /** [text] on one line, in the colors of its spans; [color] replaces the color of the style when given. */
    fun layout(text: AnnotatedString, color: Color = Color.Unspecified): LineLayout {
        val default = if (color.isSpecified) base.copy(color = color) else base
        val paragraphStyle =
            ParagraphStyle().apply {
                replaceTabCharacters = true
                textStyle = skiaStyle(default)
                // One line, as tall as its font: the row places it, not the line height of the style.
                heightMode = HeightMode.DISABLE_ALL
                maxLinesCount = 1
            }
        val builder = ParagraphBuilder(paragraphStyle, fonts)
        try {
            for (run in runsOf(text, default)) {
                builder.pushStyle(skiaStyle(run.style))
                builder.addText(text.text.substring(run.start, run.end))
                builder.popStyle()
            }
            val paragraph = builder.build()
            paragraph.layout(Float.POSITIVE_INFINITY)
            return LineLayout(paragraph)
        } finally {
            builder.close()
            paragraphStyle.close()
        }
    }

    fun layout(text: String, color: Color = Color.Unspecified): LineLayout = layout(AnnotatedString(text), color)

    override fun close() {
        styles.values.forEach { it.close() }
        styles.clear()
        fonts.close()
        typefaces.close()
    }

    private class Run(val start: Int, val end: Int, val style: SpanStyle)

    // The stretches of [text] of one style: its spans merged over [default], the later ones over the earlier.
    private fun runsOf(text: AnnotatedString, default: SpanStyle): List<Run> {
        val spans = text.spanStyles
        if (spans.isEmpty()) return listOf(Run(0, text.length, default))
        val bounds = sortedSetOf(0, text.length)
        for (span in spans) {
            bounds += span.start.coerceIn(0, text.length)
            bounds += span.end.coerceIn(0, text.length)
        }
        return bounds.zipWithNext { start, end ->
            val covering = spans.filter { it.start <= start && it.end >= end }
            Run(start, end, covering.fold(default) { merged, span -> merged.merge(span.item) })
        }
    }

    // The style of Skia of [span], as Compose builds it; kept, a theme has a few dozen.
    private fun skiaStyle(span: SpanStyle): SkTextStyle =
        styles.getOrPut(span) {
            val rasterization = FontRasterizationSettings.PlatformDefault
            val weight = span.fontWeight ?: FontWeight.Normal
            val italic = span.fontStyle == FontStyle.Italic
            SkTextStyle().apply {
                if (span.color.isSpecified) color = span.color.toArgb()
                fontStyle = (if (italic) SkFontStyle.ITALIC else SkFontStyle.NORMAL).withWeight(weight.weight)
                decorationOf(span)?.let { decorationStyle = it }
                if (span.background.isSpecified) background = Paint().also { it.color = span.background.toArgb() }
                addFontFeatures(FontFeature.parseW3(span.fontFeatureSettings.orEmpty()))
                fontEdging = edgingOf(rasterization.smoothing)
                fontHinting = hintingOf(rasterization.hinting)
                subpixel = rasterization.subpixelPositioning
                fontSize = this@LineLayouter.fontSize
                useTypeface(this, span, weight)
                if (style.lineHeight.isSpecified) height = with(density) { style.lineHeight.toPx() } / fontSize
                topRatio = PROPORTIONAL_TOP_RATIO
            }
        }

    // The font Compose resolves for [span], registered under its aliases in the fonts of the paragraphs.
    private fun useTypeface(target: SkTextStyle, span: SpanStyle, weight: FontWeight) {
        val family = span.fontFamily ?: return
        val fontStyle = span.fontStyle ?: FontStyle.Normal
        val synthesis = span.fontSynthesis ?: FontSynthesis.None
        val loaded = resolver.resolve(family, weight, fontStyle, synthesis).value as? FontLoadResult ?: return
        val typeface = loaded.typeface
        if (typeface != null) {
            for (alias in loaded.aliases) if (registered.add(alias)) typefaces.registerTypeface(typeface, alias)
        }
        target.fontFamilies = loaded.aliases.toTypedArray()
        target.typeface = typeface
    }

    private fun decorationOf(span: SpanStyle): DecorationStyle? {
        val decoration = span.textDecoration?.takeUnless { it == TextDecoration.None } ?: return null
        val underline = TextDecoration.Underline in decoration
        val lineThrough = TextDecoration.LineThrough in decoration
        return DecorationStyle(underline, false, lineThrough, false, span.color.toArgb(), DecorationLineStyle.SOLID, 1f)
    }

    private fun edgingOf(smoothing: FontSmoothing): FontEdging =
        when (smoothing) {
            FontSmoothing.None -> FontEdging.ALIAS
            FontSmoothing.AntiAlias -> FontEdging.ANTI_ALIAS
            else -> FontEdging.SUBPIXEL_ANTI_ALIAS
        }

    private fun hintingOf(hinting: FontHinting): SkFontHinting =
        when (hinting) {
            FontHinting.None -> SkFontHinting.NONE
            FontHinting.Slight -> SkFontHinting.SLIGHT
            FontHinting.Normal -> SkFontHinting.NORMAL
            else -> SkFontHinting.FULL
        }

    private companion object {
        // The extra height of a line goes above and below it in proportion to its ascent and descent.
        const val PROPORTIONAL_TOP_RATIO = -1f

        // The size Compose gives a text whose style has none.
        val DEFAULT_FONT_SIZE = 14.sp
    }
}

/** A cache of [capacity] values that own native memory: the least recently used one is closed when dropped. */
internal class ClosingLruMap<K, V : AutoCloseable>(private val capacity: Int) :
    LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        val full = size > capacity
        if (full) eldest.value.close()
        return full
    }

    fun closeAll() {
        values.forEach { it.close() }
        clear()
    }
}

private const val LOAD_FACTOR = 0.75f
