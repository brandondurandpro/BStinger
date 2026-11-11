package org.example.kotlineditor.state

/** A part of a line of output drawn with a color and/or in bold: [start] included, [end] excluded. */
data class StyleSpan(val start: Int, val end: Int, val color: Int?, val bold: Boolean)

/** A line of output without its escape sequences, and the parts of it that were colored. */
data class StyledLine(val text: String, val spans: List<StyleSpan>)

private val ESCAPE_SEQUENCE = Regex("\u001B\\[([0-9;:?]*)([ -/]*)([@-~])")

private const val OPAQUE = 0xFF000000.toInt()
private const val PARAMETERS_GROUP = 1
private const val FINAL_BYTE_GROUP = 3
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val MAX_BYTE = 255

// The 16 colors of a terminal (xterm), readable on a light or a dark background.
private val BASIC_COLORS =
    intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    ).map { OPAQUE or it }.toIntArray()

private const val FIRST_BRIGHT = 8
private const val CUBE_START = 16
private const val GRAY_START = 232
private const val CUBE_SIZE = 6
private const val CUBE_STEP = 40
private const val CUBE_BASE = 55
private const val GRAY_STEP = 10
private const val GRAY_BASE = 8
private const val BYTE = 256

private fun paletteColor(index: Int): Int? =
    when {
        index < 0 || index >= BYTE -> null
        index < BASIC_COLORS.size -> BASIC_COLORS[index]
        index < GRAY_START -> cubeColor(index - CUBE_START)
        else -> grayColor(index - GRAY_START)
    }

private fun cubeLevel(step: Int): Int = if (step == 0) 0 else CUBE_BASE + step * CUBE_STEP

private fun cubeColor(offset: Int): Int {
    val red = cubeLevel(offset / (CUBE_SIZE * CUBE_SIZE))
    val green = cubeLevel(offset / CUBE_SIZE % CUBE_SIZE)
    val blue = cubeLevel(offset % CUBE_SIZE)
    return OPAQUE or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
}

private fun grayColor(offset: Int): Int {
    val level = GRAY_BASE + offset * GRAY_STEP
    return OPAQUE or (level shl RED_SHIFT) or (level shl GREEN_SHIFT) or level
}

/**
 * Reads the colors a program prints (SGR escape sequences) line after line: a color stays on until it is reset, even
 * across lines. Other escape sequences (cursor moves, erasing) are dropped. Colors are 0xAARRGGBB.
 */
class AnsiParser {
    private var color: Int? = null
    private var bold = false

    /** [raw] as its text and the colored parts of it. */
    fun parse(raw: String): StyledLine {
        if (raw.indexOf('\u001B') < 0) return parsePlain(raw)
        val text = StringBuilder()
        val spans = ArrayList<StyleSpan>()
        var from = 0
        var spanStart = 0
        for (match in ESCAPE_SEQUENCE.findAll(raw)) {
            text.append(raw, from, match.range.first)
            from = match.range.last + 1
            if (match.groupValues[FINAL_BYTE_GROUP] != "m") continue
            closeSpan(spans, spanStart, text.length)
            apply(match.groupValues[PARAMETERS_GROUP])
            spanStart = text.length
        }
        text.append(raw, from, raw.length)
        closeSpan(spans, spanStart, text.length)
        return StyledLine(text.toString(), spans)
    }

    private fun parsePlain(raw: String): StyledLine {
        val styled = color != null || bold
        val spans = if (styled && raw.isNotEmpty()) listOf(StyleSpan(0, raw.length, color, bold)) else emptyList()
        return StyledLine(raw, spans)
    }

    private fun closeSpan(spans: MutableList<StyleSpan>, start: Int, end: Int) {
        val styled = color != null || bold
        if (styled && end > start) spans += StyleSpan(start, end, color, bold)
    }

    private fun apply(parameters: String) {
        val codes = parameters.split(';', ':').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
        var index = 0
        while (index < codes.size) {
            index += applyCode(codes, index)
        }
    }

    // Applies the code at [index]; returns how many codes it used.
    @Suppress("MagicNumber")
    private fun applyCode(codes: List<Int>, index: Int): Int {
        val code = codes[index]
        if (code == 38) return extendedColor(codes, index)
        when (code) {
            0 -> {
                color = null
                bold = false
            }

            1 -> {
                bold = true
            }

            22 -> {
                bold = false
            }

            39 -> {
                color = null
            }

            in 30..37 -> {
                color = BASIC_COLORS[code - 30]
            }

            in 90..97 -> {
                color = BASIC_COLORS[code - 90 + FIRST_BRIGHT]
            }
        }
        return 1
    }

    // `38;5;n` (palette) and `38;2;r;g;b` (true color).
    @Suppress("MagicNumber")
    private fun extendedColor(codes: List<Int>, index: Int): Int {
        val kind = codes.getOrNull(index + 1)
        val used =
            when (kind) {
                5 -> 3
                2 -> 5
                else -> 1
            }
        color =
            when (kind) {
                5 -> codes.getOrNull(index + 2)?.let(::paletteColor)
                2 -> trueColor(codes.getOrNull(index + 2), codes.getOrNull(index + 3), codes.getOrNull(index + 4))
                else -> color
            }
        return used
    }

    private fun trueColor(red: Int?, green: Int?, blue: Int?): Int {
        val r = (red ?: 0).coerceIn(0, MAX_BYTE)
        val g = (green ?: 0).coerceIn(0, MAX_BYTE)
        val b = (blue ?: 0).coerceIn(0, MAX_BYTE)
        return OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
    }
}
