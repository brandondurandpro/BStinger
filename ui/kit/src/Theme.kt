package org.example.kotlineditor.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import org.example.kotlineditor.resources.geist_bold
import org.example.kotlineditor.resources.geist_medium
import org.example.kotlineditor.resources.geist_regular
import org.example.kotlineditor.resources.geist_semibold
import org.example.kotlineditor.resources.jetbrains_mono_italic
import org.example.kotlineditor.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/** Every color of the IDE; [IdeColors] reads the active one. */
@Immutable
class IdePalette(
    /** Whether it is a dark theme. */
    val dark: Boolean,
    val panel: Color,
    /** Background of the window behind the islands (the tool windows and the editors). */
    val frame: Color,
    val editor: Color,
    val border: Color,
    val separator: Color,
    val text: Color,
    val textSecondary: Color,
    val hover: Color,
    val selection: Color,
    val selectionInactive: Color,
    /** The primary color (shadcn's `primary`): filled buttons, checked boxes, the active tab. */
    val accent: Color,
    /** Text and icons drawn on [accent]. */
    val onAccent: Color,
    /** Focus ring of inputs and buttons. */
    val ring: Color,
    /** Shadow of popups, menus and dialogs. */
    val shadow: Color,
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
        panel = Color(0xFF242428),
        frame = Color(0xFF1C1C1F),
        editor = Color(0xFF212124),
        border = Color(0xFF34343A),
        separator = Color(0xFF34343A),
        text = Color(0xFFFAFAFA),
        textSecondary = Color(0xFFA1A1AA),
        hover = Color(0xFF2E2E33),
        selection = Color(0xFF3A3A41),
        selectionInactive = Color(0xFF303036),
        accent = Color(0xFFFAFAFA),
        onAccent = Color(0xFF18181B),
        ring = Color(0xFFD4D4D8),
        shadow = Color(0x66000000),
        accentHover = Color(0xFFE4E4E7),
        error = Color(0xFFF87171),
        input = Color(0xFF212124),
        inputBorder = Color(0xFF3A3A41),
        popup = Color(0xFF242428),
        code = Color(0xFFF4F4F5),
        lineNumber = Color(0xFF62626C),
        lineNumberCurrent = Color(0xFFFAFAFA),
        currentLine = Color(0xFF2A2A2E),
        searchMatch = Color(0xFF1F4D33),
        searchMatchCurrent = Color(0xFF1E4A7A),
        caret = Color(0xFFFAFAFA),
        textSelection = Color(0xFF2A3A5C),
        bracketMatch = Color(0xFF27403A),
        bracketDepths = listOf(Color(0xFFFFD700), Color(0xFFDA70D6), Color(0xFF179FFF)),
        bracketUnmatched = Color(0xFFF75464),
        identifierUsage = Color(0xFF34343A),
        identifierWrite = Color(0xFF3A2440),
        problemError = Color(0xFFF87171),
        problemWarning = Color(0xFFFBBF24),
        problemInformation = Color(0xFF71717A),
        inlayHint = Color(0xFF71717A),
        documentationCode = Color(0xFF2A2A2E),
        foldPlaceholder = Color(0xFF34343A),
        semanticType = Color(0xFF4ADE80),
        semanticCall = Color(0xFF38BDF8),
        semanticParameter = Color(0xFFFCD34D),
        inlayHintBackground = Color(0xFF2A2A2E),
        keyword = Color(0xFFC084FC),
        stringLiteral = Color(0xFFFDBA74),
        number = Color(0xFFF472B6),
        comment = Color(0xFF71717A),
        annotation = Color(0xFFFDE047),
        functionDeclaration = Color(0xFF22D3EE),
        property = Color(0xFFF0ABFC),
        stringEscape = Color(0xFFC084FC),
        typeParameter = Color(0xFF4ADE80),
        label = Color(0xFF32B8AF),
        markupTag = Color(0xFFC084FC),
        markupAttribute = Color(0xFF38BDF8),
        link = Color(0xFF60A5FA),
        folder = Color(0xFFA1A1AA),
        gitAdded = Color(0xFF4ADE80),
        gitModified = Color(0xFF60A5FA),
        gitDeleted = Color(0xFF71717A),
        gitUntracked = Color(0xFFFB923C),
        gitAhead = Color(0xFF4ADE80),
        gitBehind = Color(0xFFFBBF24),
        diffAddedBackground = Color(0xFF0F2E1C),
        diffRemovedBackground = Color(0xFF3B1619),
        diffHunkBackground = Color(0xFF2A2A2E),
        mergeConflictBackground = Color(0xFF3A2E0E),
        mergeResolvedBackground = Color(0xFF132840),
        kindClass = Color(0xFF3B82F6),
        kindInterface = Color(0xFF14B8A6),
        kindObject = Color(0xFF8B5CF6),
        kindFunction = Color(0xFFF97316),
        kindProperty = Color(0xFFA855F7),
        modified = Color(0xFF60A5FA),
        bannerBackground = Color(0xFF2A2A2E),
        bannerBorder = Color(0xFF3F3F46),
    )

val LightPalette =
    IdePalette(
        dark = false,
        panel = Color(0xFFFFFFFF),
        frame = Color(0xFFF0F0F2),
        editor = Color(0xFFFFFFFF),
        border = Color(0xFFE4E4E7),
        separator = Color(0xFFE4E4E7),
        text = Color(0xFF09090B),
        textSecondary = Color(0xFF71717A),
        hover = Color(0xFFF4F4F5),
        selection = Color(0xFFE4E4E7),
        selectionInactive = Color(0xFFF4F4F5),
        accent = Color(0xFF18181B),
        onAccent = Color(0xFFFAFAFA),
        ring = Color(0xFFA1A1AA),
        shadow = Color(0x1F000000),
        accentHover = Color(0xFF27272A),
        error = Color(0xFFDC2626),
        input = Color(0xFFFFFFFF),
        inputBorder = Color(0xFFE4E4E7),
        popup = Color(0xFFFFFFFF),
        code = Color(0xFF09090B),
        lineNumber = Color(0xFFA1A1AA),
        lineNumberCurrent = Color(0xFF18181B),
        currentLine = Color(0xFFFAFAFA),
        searchMatch = Color(0xFFBDE3C0),
        searchMatchCurrent = Color(0xFF9DC3F5),
        caret = Color(0xFF09090B),
        textSelection = Color(0xFFBFDBFE),
        bracketMatch = Color(0xFF93D9D9),
        bracketDepths = listOf(Color(0xFFB8860B), Color(0xFFB34DB0), Color(0xFF0B6FD6)),
        bracketUnmatched = Color(0xFFD6303F),
        identifierUsage = Color(0xFFE4E4FF),
        identifierWrite = Color(0xFFF5D9F5),
        problemError = Color(0xFFDC2626),
        problemWarning = Color(0xFFCA8A04),
        problemInformation = Color(0xFF71717A),
        inlayHint = Color(0xFF71717A),
        documentationCode = Color(0xFFF4F4F5),
        foldPlaceholder = Color(0xFFE4E4E7),
        semanticType = Color(0xFF16A34A),
        semanticCall = Color(0xFF0284C7),
        semanticParameter = Color(0xFF8A5A00),
        inlayHintBackground = Color(0xFFF4F4F5),
        keyword = Color(0xFF7C3AED),
        stringLiteral = Color(0xFFC2410C),
        number = Color(0xFFDB2777),
        comment = Color(0xFF71717A),
        annotation = Color(0xFF9E880D),
        functionDeclaration = Color(0xFF0891B2),
        property = Color(0xFFC026D3),
        stringEscape = Color(0xFF7C3AED),
        typeParameter = Color(0xFF16A34A),
        label = Color(0xFF007E8A),
        markupTag = Color(0xFF7C3AED),
        markupAttribute = Color(0xFF0284C7),
        link = Color(0xFF2563EB),
        folder = Color(0xFF71717A),
        gitAdded = Color(0xFF16A34A),
        gitModified = Color(0xFF2563EB),
        gitDeleted = Color(0xFFA1A1AA),
        gitUntracked = Color(0xFFEA580C),
        gitAhead = Color(0xFF16A34A),
        gitBehind = Color(0xFFD97706),
        diffAddedBackground = Color(0xFFDCFCE7),
        diffRemovedBackground = Color(0xFFFEE2E2),
        diffHunkBackground = Color(0xFFF4F4F5),
        mergeConflictBackground = Color(0xFFFEF3C7),
        mergeResolvedBackground = Color(0xFFDBEAFE),
        kindClass = Color(0xFF2563EB),
        kindInterface = Color(0xFF0D9488),
        kindObject = Color(0xFF7C3AED),
        kindFunction = Color(0xFFEA580C),
        kindProperty = Color(0xFF9333EA),
        modified = Color(0xFF2563EB),
        bannerBackground = Color(0xFFF4F4F5),
        bannerBorder = Color(0xFFD4D4D8),
    )

/** Colors of the active theme (shadcn/ui's zinc palette): reading one inside a composable makes it follow the theme. */
object IdeColors {
    /** The palette in use; set by [IdeTheme]. */
    var palette by mutableStateOf(DarkPalette)
        internal set

    val Panel get() = palette.panel
    val Frame get() = palette.frame
    val Editor get() = palette.editor
    val Border get() = palette.border
    val Separator get() = palette.separator
    val Text get() = palette.text
    val TextSecondary get() = palette.textSecondary
    val Hover get() = palette.hover
    val Selection get() = palette.selection
    val SelectionInactive get() = palette.selectionInactive
    val Accent get() = palette.accent
    val OnAccent get() = palette.onAccent
    val Ring get() = palette.ring
    val Shadow get() = palette.shadow
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

    /** Font of the chrome (labels, buttons, menus): Geist, shadcn/ui's font, once [IdeTheme] has loaded it. */
    var ui: FontFamily by mutableStateOf(FontFamily.Default)
        internal set
}

/** Corner radii of shadcn/ui (`rounded-sm` to `rounded-xl`), for every rounded surface of the chrome. */
object IdeShapes {
    /** Badges, check boxes, small chips. */
    val Small = RoundedCornerShape(4.dp)

    /** Buttons, inputs, list rows and menu items. */
    val Medium = RoundedCornerShape(6.dp)

    /** Popovers, menus, cards. */
    val Large = RoundedCornerShape(8.dp)

    /** Dialogs. */
    val ExtraLarge = RoundedCornerShape(12.dp)

    /** The islands: tool windows and editors, floating on the frame of the window. */
    val Island = RoundedCornerShape(10.dp)

    /** Pills: toggles, counters. */
    val Full = RoundedCornerShape(percent = 50)
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
    val regular = Font(Res.font.geist_regular, FontWeight.Normal)
    val medium = Font(Res.font.geist_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.geist_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.geist_bold, FontWeight.Bold)
    val ui = remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
    // Compared without reading the state, so that setting it never recomposes the theme itself.
    val current = Snapshot.withoutReadObservation { IdeFonts.ui }
    if (current != ui) IdeFonts.ui = ui
    val colorScheme = materialColors(palette)
    val typography = remember(ui) { materialTypography(ui) }
    val shapes = remember { Shapes(extraSmall = IdeShapes.Large, small = IdeShapes.Medium, medium = IdeShapes.Large) }
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(
            LocalCodeStyle provides codeStyle,
            LocalScrollbarStyle provides
                ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 8.dp,
                    shape = IdeShapes.Full,
                    hoverDurationMillis = 150,
                    unhoverColor = palette.textSecondary.copy(alpha = 0.25f),
                    hoverColor = palette.textSecondary.copy(alpha = 0.45f),
                ),
            content = content,
        )
    }
}

private fun materialTypography(ui: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        bodyLarge = base.bodyLarge.copy(fontFamily = ui),
        bodyMedium = base.bodyMedium.copy(fontFamily = ui),
        bodySmall = base.bodySmall.copy(fontFamily = ui),
        labelLarge = base.labelLarge.copy(fontFamily = ui),
        labelMedium = base.labelMedium.copy(fontFamily = ui),
        labelSmall = base.labelSmall.copy(fontFamily = ui),
        titleSmall = base.titleSmall.copy(fontFamily = ui),
        titleMedium = base.titleMedium.copy(fontFamily = ui),
    )
}

// Material's components (menus, progress) wear shadcn's tokens: flat popover surfaces, no tonal tint.
private fun materialColors(palette: IdePalette): ColorScheme {
    val base = if (palette.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        background = palette.panel,
        onBackground = palette.text,
        surface = palette.popup,
        onSurface = palette.text,
        onSurfaceVariant = palette.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainer = palette.popup,
        surfaceContainerLow = palette.popup,
        surfaceContainerHigh = palette.popup,
        outline = palette.border,
        outlineVariant = palette.separator,
        error = palette.error,
    )
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
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    val style =
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = IdeFonts.ui,
            letterSpacing = letterSpacing,
        )
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Colors of the Git status of a file. */
fun gitColor(status: GitFileStatus): Color =
    when (status) {
        GitFileStatus.Modified, GitFileStatus.Renamed -> IdeColors.Modified
        GitFileStatus.Added -> IdeColors.GitAdded
        GitFileStatus.Untracked -> IdeColors.GitUntracked
        GitFileStatus.Deleted -> IdeColors.TextSecondary
        GitFileStatus.Conflicted -> IdeColors.Error
    }
