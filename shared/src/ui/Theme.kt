package org.example.kotlineditor.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.resources.Res
import org.example.kotlineditor.resources.jetbrains_mono_italic
import org.example.kotlineditor.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/** IntelliJ IDEA "Dark" (New UI) palette. */
object IdeColors {
    val Panel = Color(0xFF2B2D30)
    val Editor = Color(0xFF1E1F22)
    val Border = Color(0xFF1E1F22)
    val Separator = Color(0xFF393B40)
    val Text = Color(0xFFDFE1E5)
    val TextSecondary = Color(0xFF868A91)
    val Hover = Color(0xFF393B40)
    val Selection = Color(0xFF2E436E)
    val SelectionInactive = Color(0xFF43454A)
    val Accent = Color(0xFF3574F0)
    val AccentHover = Color(0xFF3369D6)
    val Error = Color(0xFFDB5C5C)
    val Input = Color(0xFF1E1F22)
    val InputBorder = Color(0xFF4E5157)
    val Popup = Color(0xFF2B2D30)

    val Code = Color(0xFFBCBEC4)
    val LineNumber = Color(0xFF4B5059)
    val LineNumberCurrent = Color(0xFFA1A3AB)
    val CurrentLine = Color(0xFF26282E)
    val SearchMatch = Color(0xFF32593D)
    val SearchMatchCurrent = Color(0xFF2D5B8C)
    val Caret = Color(0xFFCED0D6)
    val TextSelection = Color(0xFF214283)
    val BracketMatch = Color(0xFF3B514D)
    val IdentifierUsage = Color(0xFF373B39)
    val FoldPlaceholder = Color(0xFF393B40)

    val Keyword = Color(0xFFCF8E6D)
    val StringLiteral = Color(0xFF6AAB73)
    val Number = Color(0xFF2AACB8)
    val Comment = Color(0xFF7A7E85)
    val Annotation = Color(0xFFB3AE60)
    val FunctionDeclaration = Color(0xFF56A8F5)

    val Folder = Color(0xFF9AA7B0)
    val GitAdded = Color(0xFF73BD79)
    val GitModified = Color(0xFF4E7DD4)
    val GitDeleted = Color(0xFF7E838B)
    val GitUntracked = Color(0xFFD1675A)
    val DiffAddedBackground = Color(0xFF294436)
    val DiffRemovedBackground = Color(0xFF4A2E30)
    val DiffHunkBackground = Color(0xFF252A33)
    val KindClass = Color(0xFF3D8BD1)
    val KindInterface = Color(0xFF5FB2A7)
    val KindObject = Color(0xFF8F6CF5)
    val KindFunction = Color(0xFFD9853B)
    val KindProperty = Color(0xFF9B7FD1)
    val Modified = Color(0xFF6C9BEA)
}

object IdeFonts {
    /** Fallback only: [IdeTheme] provides the bundled JetBrains Mono through [LocalCodeStyle]. */
    val Code =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = IdeColors.Code)
}

/** Text style of code. Every glyph must have the same advance: caret, selection and clicks rely on it. */
val LocalCodeStyle = staticCompositionLocalOf { IdeFonts.Code }

@Composable
fun IdeTheme(content: @Composable () -> Unit) {
    // FontFamily.Monospace may resolve to a proportional font on Linux, so the IDE ships JetBrains Mono (OFL-1.1).
    val mono =
        FontFamily(
            Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
            Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
        )
    // Ligatures off, as in IntelliJ's defaults.
    val codeStyle = remember(mono) { IdeFonts.Code.copy(fontFamily = mono, fontFeatureSettings = "liga 0, calt 0") }
    MaterialTheme(
        colorScheme =
        darkColorScheme(
            primary = IdeColors.Accent,
            background = IdeColors.Panel,
            surface = IdeColors.Popup,
            onSurface = IdeColors.Text,
            onBackground = IdeColors.Text,
        ),
    ) {
        CompositionLocalProvider(
            LocalCodeStyle provides codeStyle,
            LocalScrollbarStyle provides
                ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 200,
                    unhoverColor = Color(0x59A6A6A6),
                    hoverColor = Color(0x80A6A6A6),
                ),
            content = content,
        )
    }
}

/** Lightweight label: [BasicText] is cheaper than Material's Text and is what IDE chrome needs. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = IdeColors.Text,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
