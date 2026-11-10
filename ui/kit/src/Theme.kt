package org.example.kotlineditor.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.resources.Res
import org.example.kotlineditor.resources.jetbrains_mono_italic
import org.example.kotlineditor.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/** Every color of the IDE; [IdeColors] reads the active one. */
@Immutable
class IdePalette(
    /** Whether it is a dark theme. */
    val dark: Boolean,
    val panel: Color,
    val editor: Color,
    val border: Color,
    val separator: Color,
    val text: Color,
    val textSecondary: Color,
    val hover: Color,
    val selection: Color,
    val selectionInactive: Color,
    val accent: Color,
    val accentHover: Color,
    val error: Color,
    val input: Color,
    val inputBorder: Color,
    val popup: Color,
    val code: Color,
    val lineNumber: Color,
    val lineNumberCurrent: Color,
    val currentLine: Color,
    val searchMatch: Color,
    val searchMatchCurrent: Color,
    val caret: Color,
    val textSelection: Color,
    val bracketMatch: Color,
    /** Colors of brackets by nesting depth, in turn (VS Code's bracket pair colorization). */
    val bracketDepths: List<Color>,
    /** A closing bracket that nothing opened. */
    val bracketUnmatched: Color,
    val identifierUsage: Color,
    val identifierWrite: Color,
    val problemError: Color,
    val problemWarning: Color,
    val problemInformation: Color,
    val inlayHint: Color,
    val documentationCode: Color,
    val foldPlaceholder: Color,
    /** A class, interface or enum, as the language server tells (semantic tokens). */
    val semanticType: Color,
    /** A call of a function or method, as the language server tells. */
    val semanticCall: Color,
    /** A parameter, as the language server tells. */
    val semanticParameter: Color,
    /** Background of an inlay hint. */
    val inlayHintBackground: Color,
    val keyword: Color,
    val stringLiteral: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val functionDeclaration: Color,
    val property: Color,
    val stringEscape: Color,
    val typeParameter: Color,
    val label: Color,
    val markupTag: Color,
    val markupAttribute: Color,
    val link: Color,
    val folder: Color,
    val gitAdded: Color,
    val gitModified: Color,
    val gitDeleted: Color,
    val gitUntracked: Color,
    /** Commits to push (`↑`) and to pull (`↓`). */
    val gitAhead: Color,
    val gitBehind: Color,
    val diffAddedBackground: Color,
    val diffRemovedBackground: Color,
    val diffHunkBackground: Color,
    val mergeConflictBackground: Color,
    val mergeResolvedBackground: Color,
    val kindClass: Color,
    val kindInterface: Color,
    val kindObject: Color,
    val kindFunction: Color,
    val kindProperty: Color,
    val modified: Color,
    /** Background of an informational banner and its border. */
    val bannerBackground: Color,
    val bannerBorder: Color,
)

val DarkPalette =
    IdePalette(
        dark = true,
        panel = Color(0xFF2B2D30),
        editor = Color(0xFF1E1F22),
        border = Color(0xFF1E1F22),
        separator = Color(0xFF393B40),
        text = Color(0xFFDFE1E5),
        textSecondary = Color(0xFF868A91),
        hover = Color(0xFF393B40),
        selection = Color(0xFF2E436E),
        selectionInactive = Color(0xFF43454A),
        accent = Color(0xFF3574F0),
        accentHover = Color(0xFF3369D6),
        error = Color(0xFFDB5C5C),
        input = Color(0xFF1E1F22),
        inputBorder = Color(0xFF4E5157),
        popup = Color(0xFF2B2D30),
        code = Color(0xFFBCBEC4),
        lineNumber = Color(0xFF4B5059),
        lineNumberCurrent = Color(0xFFA1A3AB),
        currentLine = Color(0xFF26282E),
        searchMatch = Color(0xFF32593D),
        searchMatchCurrent = Color(0xFF2D5B8C),
        caret = Color(0xFFCED0D6),
        textSelection = Color(0xFF214283),
        bracketMatch = Color(0xFF3B514D),
        bracketDepths = listOf(Color(0xFFFFD700), Color(0xFFDA70D6), Color(0xFF179FFF)),
        bracketUnmatched = Color(0xFFF75464),
        identifierUsage = Color(0xFF373B39),
        identifierWrite = Color(0xFF472C47),
        problemError = Color(0xFFF75464),
        problemWarning = Color(0xFFE5B53D),
        problemInformation = Color(0xFF7E838B),
        inlayHint = Color(0xFF868A91),
        documentationCode = Color(0xFF1E1F22),
        foldPlaceholder = Color(0xFF393B40),
        semanticType = Color(0xFF6FAFBD),
        semanticCall = Color(0xFF57AAF7),
        semanticParameter = Color(0xFFD0A86C),
        inlayHintBackground = Color(0xFF3B3D42),
        keyword = Color(0xFFCF8E6D),
        stringLiteral = Color(0xFF6AAB73),
        number = Color(0xFF2AACB8),
        comment = Color(0xFF7A7E85),
        annotation = Color(0xFFB3AE60),
        functionDeclaration = Color(0xFF56A8F5),
        property = Color(0xFFC77DBB),
        stringEscape = Color(0xFFCF8E6D),
        typeParameter = Color(0xFF16BAAC),
        label = Color(0xFF32B8AF),
        markupTag = Color(0xFFD5B778),
        markupAttribute = Color(0xFFBABABA),
        link = Color(0xFF548AF7),
        folder = Color(0xFF9AA7B0),
        gitAdded = Color(0xFF73BD79),
        gitModified = Color(0xFF4E7DD4),
        gitDeleted = Color(0xFF7E838B),
        gitUntracked = Color(0xFFD1675A),
        gitAhead = Color(0xFF73BD79),
        gitBehind = Color(0xFFE0A458),
        diffAddedBackground = Color(0xFF294436),
        diffRemovedBackground = Color(0xFF4A2E30),
        diffHunkBackground = Color(0xFF252A33),
        mergeConflictBackground = Color(0xFF4D3F1E),
        mergeResolvedBackground = Color(0xFF263B4F),
        kindClass = Color(0xFF3D8BD1),
        kindInterface = Color(0xFF5FB2A7),
        kindObject = Color(0xFF8F6CF5),
        kindFunction = Color(0xFFD9853B),
        kindProperty = Color(0xFF9B7FD1),
        modified = Color(0xFF6C9BEA),
        bannerBackground = Color(0xFF25324D),
        bannerBorder = Color(0xFF35538F),
    )

val LightPalette =
    IdePalette(
        dark = false,
        panel = Color(0xFFF7F8FA),
        editor = Color(0xFFFFFFFF),
        border = Color(0xFFEBECF0),
        separator = Color(0xFFDFE1E5),
        text = Color(0xFF1E1F22),
        textSecondary = Color(0xFF6C707E),
        hover = Color(0xFFEBECF0),
        selection = Color(0xFFD4E2FF),
        selectionInactive = Color(0xFFDFE1E5),
        accent = Color(0xFF3574F0),
        accentHover = Color(0xFF315FBD),
        error = Color(0xFFE55765),
        input = Color(0xFFFFFFFF),
        inputBorder = Color(0xFFC9CCD6),
        popup = Color(0xFFFFFFFF),
        code = Color(0xFF080808),
        lineNumber = Color(0xFFAEB3C2),
        lineNumberCurrent = Color(0xFF6C707E),
        currentLine = Color(0xFFFCFAED),
        searchMatch = Color(0xFFBDE3C0),
        searchMatchCurrent = Color(0xFF9DC3F5),
        caret = Color(0xFF000000),
        textSelection = Color(0xFFA6D2FF),
        bracketMatch = Color(0xFF93D9D9),
        bracketDepths = listOf(Color(0xFFB8860B), Color(0xFFB34DB0), Color(0xFF0B6FD6)),
        bracketUnmatched = Color(0xFFD6303F),
        identifierUsage = Color(0xFFE4E4FF),
        identifierWrite = Color(0xFFF5D9F5),
        problemError = Color(0xFFE55765),
        problemWarning = Color(0xFFC99A00),
        problemInformation = Color(0xFF818594),
        inlayHint = Color(0xFF6C707E),
        documentationCode = Color(0xFFF2F4F7),
        foldPlaceholder = Color(0xFFDFE1E5),
        semanticType = Color(0xFF0B7285),
        semanticCall = Color(0xFF0A5DAB),
        semanticParameter = Color(0xFF8A5A00),
        inlayHintBackground = Color(0xFFEDEEF2),
        keyword = Color(0xFF0033B3),
        stringLiteral = Color(0xFF067D17),
        number = Color(0xFF1750EB),
        comment = Color(0xFF8C8C8C),
        annotation = Color(0xFF9E880D),
        functionDeclaration = Color(0xFF00627A),
        property = Color(0xFF871094),
        stringEscape = Color(0xFF0037A6),
        typeParameter = Color(0xFF007E8A),
        label = Color(0xFF007E8A),
        markupTag = Color(0xFF0033B3),
        markupAttribute = Color(0xFF174AD4),
        link = Color(0xFF2470B3),
        folder = Color(0xFF6E7B87),
        gitAdded = Color(0xFF3C8C3C),
        gitModified = Color(0xFF2D5AD0),
        gitDeleted = Color(0xFF818594),
        gitUntracked = Color(0xFFC1502E),
        gitAhead = Color(0xFF3C8C3C),
        gitBehind = Color(0xFFB77A1E),
        diffAddedBackground = Color(0xFFD5F0D5),
        diffRemovedBackground = Color(0xFFF7D4D4),
        diffHunkBackground = Color(0xFFEEF1F6),
        mergeConflictBackground = Color(0xFFFBEBC8),
        mergeResolvedBackground = Color(0xFFDCE9F7),
        kindClass = Color(0xFF2F6FBF),
        kindInterface = Color(0xFF2B8F82),
        kindObject = Color(0xFF6F4BD6),
        kindFunction = Color(0xFFB8631A),
        kindProperty = Color(0xFF7A58B8),
        modified = Color(0xFF2D5AD0),
        bannerBackground = Color(0xFFE8F0FE),
        bannerBorder = Color(0xFFB3CBF5),
    )

/** IntelliJ IDEA (New UI) colors of the active theme: reading one inside a composable makes it follow the theme. */
object IdeColors {
    /** The palette in use; set by [IdeTheme]. */
    var palette by mutableStateOf(DarkPalette)
        internal set

    val Panel get() = palette.panel
    val Editor get() = palette.editor
    val Border get() = palette.border
    val Separator get() = palette.separator
    val Text get() = palette.text
    val TextSecondary get() = palette.textSecondary
    val Hover get() = palette.hover
    val Selection get() = palette.selection
    val SelectionInactive get() = palette.selectionInactive
    val Accent get() = palette.accent
    val AccentHover get() = palette.accentHover
    val Error get() = palette.error
    val Input get() = palette.input
    val InputBorder get() = palette.inputBorder
    val Popup get() = palette.popup
    val Code get() = palette.code
    val LineNumber get() = palette.lineNumber
    val LineNumberCurrent get() = palette.lineNumberCurrent
    val CurrentLine get() = palette.currentLine
    val SearchMatch get() = palette.searchMatch
    val SearchMatchCurrent get() = palette.searchMatchCurrent
    val Caret get() = palette.caret
    val TextSelection get() = palette.textSelection
    val BracketMatch get() = palette.bracketMatch

    /** Colors of brackets by nesting depth, in turn (VS Code's bracket pair colorization). */
    val BracketDepths get() = palette.bracketDepths

    /** A closing bracket that nothing opened. */
    val BracketUnmatched get() = palette.bracketUnmatched
    val IdentifierUsage get() = palette.identifierUsage
    val IdentifierWrite get() = palette.identifierWrite
    val ProblemError get() = palette.problemError
    val ProblemWarning get() = palette.problemWarning
    val ProblemInformation get() = palette.problemInformation
    val InlayHint get() = palette.inlayHint
    val DocumentationCode get() = palette.documentationCode
    val FoldPlaceholder get() = palette.foldPlaceholder

    /** A class, interface or enum, as the language server tells (semantic tokens). */
    val SemanticType get() = palette.semanticType

    /** A call of a function or method, as the language server tells. */
    val SemanticCall get() = palette.semanticCall

    /** A parameter, as the language server tells. */
    val SemanticParameter get() = palette.semanticParameter

    /** Background of an inlay hint. */
    val InlayHintBackground get() = palette.inlayHintBackground
    val Keyword get() = palette.keyword
    val StringLiteral get() = palette.stringLiteral
    val Number get() = palette.number
    val Comment get() = palette.comment
    val Annotation get() = palette.annotation
    val FunctionDeclaration get() = palette.functionDeclaration
    val Property get() = palette.property
    val StringEscape get() = palette.stringEscape
    val TypeParameter get() = palette.typeParameter
    val Label get() = palette.label
    val MarkupTag get() = palette.markupTag
    val MarkupAttribute get() = palette.markupAttribute
    val Link get() = palette.link
    val Folder get() = palette.folder
    val GitAdded get() = palette.gitAdded
    val GitModified get() = palette.gitModified
    val GitDeleted get() = palette.gitDeleted
    val GitUntracked get() = palette.gitUntracked

    /** Commits to push (`↑`) and to pull (`↓`). */
    val GitAhead get() = palette.gitAhead
    val GitBehind get() = palette.gitBehind
    val DiffAddedBackground get() = palette.diffAddedBackground
    val DiffRemovedBackground get() = palette.diffRemovedBackground
    val DiffHunkBackground get() = palette.diffHunkBackground

    /** A conflict nobody resolved yet in the three-way merge, and the stretch of the result once resolved. */
    val MergeConflictBackground get() = palette.mergeConflictBackground
    val MergeResolvedBackground get() = palette.mergeResolvedBackground
    val KindClass get() = palette.kindClass
    val KindInterface get() = palette.kindInterface
    val KindObject get() = palette.kindObject
    val KindFunction get() = palette.kindFunction
    val KindProperty get() = palette.kindProperty
    val Modified get() = palette.modified
    val BannerBackground get() = palette.bannerBackground
    val BannerBorder get() = palette.bannerBorder
}

object IdeFonts {
    /** Fallback only: [IdeTheme] provides the bundled JetBrains Mono through [LocalCodeStyle]. */
    val Code =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = IdeColors.Code)
}

/** Text style of code. Every glyph must have the same advance: caret, selection and clicks rely on it. */
val LocalCodeStyle = staticCompositionLocalOf { IdeFonts.Code }

/** Which palette the IDE wears: one of the two, or the one of the operating system. */
enum class ThemeChoice(val title: String) {
    System("System"),
    Dark("Dark"),
    Light("Light"),
    ;

    /** The palette to wear, [systemDark] telling whether the operating system is dark. */
    fun paletteFor(systemDark: Boolean): IdePalette =
        when (this) {
            System -> if (systemDark) DarkPalette else LightPalette
            Dark -> DarkPalette
            Light -> LightPalette
        }

    companion object {
        /** The theme saved as [saved] (a [name]); [Dark] when none or unknown. */
        fun fromSaved(saved: String?): ThemeChoice = entries.firstOrNull { it.name == saved } ?: Dark
    }
}

@Composable
fun IdeTheme(choice: ThemeChoice = ThemeChoice.Dark, content: @Composable () -> Unit) {
    val palette = choice.paletteFor(isSystemInDarkTheme())
    // Set while composing, before any child reads it, so that no frame is drawn in the previous theme.
    if (IdeColors.palette !== palette) IdeColors.palette = palette
    // FontFamily.Monospace may resolve to a proportional font on Linux, so the IDE ships JetBrains Mono (OFL-1.1).
    val mono =
        FontFamily(
            Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
            Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
        )
    // Ligatures off, as in IntelliJ's defaults.
    val codeStyle =
        remember(mono, palette) {
            IdeFonts.Code.copy(
                fontFamily = mono,
                fontFeatureSettings = "liga 0, calt 0",
                color = palette.code,
            )
        }
    val colorScheme = materialColors(palette)
    MaterialTheme(colorScheme = colorScheme) {
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

private fun materialColors(palette: IdePalette): ColorScheme {
    val primary = palette.accent
    val background = palette.panel
    val surface = palette.popup
    val onSurface = palette.text
    return if (palette.dark) {
        darkColorScheme(
            primary = primary,
            background = background,
            surface = surface,
            onSurface = onSurface,
            onBackground = onSurface,
        )
    } else {
        lightColorScheme(
            primary = primary,
            background = background,
            surface = surface,
            onSurface = onSurface,
            onBackground = onSurface,
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
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** IntelliJ's VCS file status colors. */
fun gitColor(status: GitFileStatus): Color =
    when (status) {
        GitFileStatus.Modified, GitFileStatus.Renamed -> IdeColors.Modified
        GitFileStatus.Added -> IdeColors.GitAdded
        GitFileStatus.Untracked -> IdeColors.GitUntracked
        GitFileStatus.Deleted -> IdeColors.TextSecondary
        GitFileStatus.Conflicted -> IdeColors.Error
    }
