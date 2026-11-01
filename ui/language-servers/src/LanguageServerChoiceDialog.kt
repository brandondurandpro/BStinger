@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.state.LanguageServersState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.recommendedOf

// IntelliJ New UI Dark: editor notification banner, and the colors of its inspection results.
private val BannerBackground = Color(0xFF25324D)
private val BannerBorder = Color(0xFF35538F)
private val InfoBlue = Color(0xFF548AF7)
private val Success = Color(0xFF5FAD65)
private val Caution = Color(0xFFE0A458)
private val Backdrop = Color(0x66000000)

/**
 * IntelliJ's editor notification: a thin strip above the editors when a file needs a language server and none is
 * chosen. It blocks nothing; its links open the choice dialog, use the recommended server at once, or dismiss it.
 */
@Composable
fun LanguageServerChoiceBanner(project: ProjectState) {
    val state = project.languageServers ?: return
    val choice = state.choice ?: return
    val recommended = recommendedOf(choice)
    Column(Modifier.fillMaxWidth().testTag(LanguageServerTags.LSP_CHOICE)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(BannerBackground)
                .heightIn(min = 34.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoIcon()
            Label("${choice.languageName} code insight needs a language server", fontSize = 13.sp)
            BannerLink("Choose server…", LanguageServerTags.LSP_CHOICE_OPEN, onClick = state::openChoiceDialog)
            if (recommended != null) {
                BannerLink(
                    "Use ${recommended.displayName}",
                    LanguageServerTags.LSP_CHOICE_QUICK,
                    enabled = !state.choosing,
                ) {
                    state.choose(recommended.id)
                }
            }
            Spacer(Modifier.weight(1f))
            BannerLink("Not now", LanguageServerTags.LSP_CHOICE_NOT_NOW, onClick = state::notNow)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BannerBorder))
    }
}

@Composable
private fun BannerLink(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Label(
        text,
        Modifier
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(tag),
        color = linkColor(enabled, hovered),
        fontSize = 13.sp,
    )
}

private fun linkColor(enabled: Boolean, hovered: Boolean) =
    when {
        !enabled -> IdeColors.TextSecondary
        hovered -> IdeColors.Text
        else -> InfoBlue
    }

@Composable
private fun InfoIcon() {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.3.dp.toPx()
        drawCircle(InfoBlue, radius = size.width / 2 - stroke, style = Stroke(stroke))
        val x = size.width / 2
        drawLine(InfoBlue, Offset(x, size.height * 0.45f), Offset(x, size.height * 0.72f), stroke, StrokeCap.Round)
        drawCircle(InfoBlue, radius = stroke * 0.8f, center = Offset(x, size.height * 0.3f))
    }
}

/**
 * The choice dialog, like IntelliJ's settings: the servers on the left, the selected one's advantages, limitations
 * and requirements on the right, Cancel and Use at the bottom right. Arrows select, Enter uses, Escape cancels.
 */
@Composable
fun LanguageServerChoiceDialog(project: ProjectState) {
    val state = project.languageServers
    val choice = state?.choice?.takeIf { state.choiceDialogOpen } ?: return
    val selected = choice.candidates.firstOrNull { it.id == state.selectedCandidate } ?: choice.candidates.first()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val use = { if (selected.available && !state.choosing) state.choose(selected.id) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .pointerInput(Unit) { detectTapGestures { state.closeChoiceDialog() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(760.dp)
                .height(480.dp)
                .background(IdeColors.Panel, RoundedCornerShape(10.dp))
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(10.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .dialogKeys(state, use)
                .focusRequester(focus)
                .focusable()
                .testTag(LanguageServerTags.LSP_CHOICE_DIALOG),
        ) {
            DialogTitle("Language Server for ${choice.languageName}", state::closeChoiceDialog)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                CandidateList(state, choice, selected)
                Box(Modifier.width(1.dp).fillMaxHeight().background(IdeColors.Separator))
                CandidateDetails(choice, selected, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            DialogFooter(state, selected, use)
        }
    }
}

private fun Modifier.dialogKeys(state: LanguageServersState, use: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val action: (() -> Unit)? =
            when {
                event.type != KeyEventType.KeyDown -> {
                    null
                }

                event.key == Key.DirectionDown -> {
                    { state.moveSelection(1) }
                }

                event.key == Key.DirectionUp -> {
                    { state.moveSelection(-1) }
                }

                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    use
                }

                event.key == Key.Escape -> {
                    state::closeChoiceDialog
                }

                else -> {
                    null
                }
            }
        action?.invoke()
        action != null
    }

@Composable
private fun DialogTitle(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        IconButton(onClose) { CloseIcon() }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

@Composable
private fun CandidateList(
    state: LanguageServersState,
    choice: LanguageServerChoice,
    selected: LanguageServerCandidateInfo,
) {
    val recommended = recommendedOf(choice)
    Column(
        Modifier.width(240.dp).fillMaxHeight().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (candidate in choice.candidates) {
            CandidateRow(
                candidate,
                isSelected = candidate.id == selected.id,
                isRecommended = candidate.id == recommended?.id,
                isCurrent = candidate.id == choice.chosen,
            ) { state.select(candidate.id) }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: LanguageServerCandidateInfo,
    isSelected: Boolean,
    isRecommended: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            isSelected -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .testTag(LanguageServerTags.lspChoiceOption(candidate.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProjectBadge(candidate.displayName, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(candidate.displayName, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold)
                when {
                    isCurrent -> Chip("Current", IdeColors.TextSecondary)
                    isRecommended -> Chip("Recommended", InfoBlue)
                }
            }
            Label(
                if (candidate.available) "Ready to install" else candidate.details.firstOrNull() ?: "Unavailable",
                color = if (candidate.available) Success else IdeColors.Error,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Label(
        text,
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
    )
}

@Composable
private fun CandidateDetails(
    choice: LanguageServerChoice,
    candidate: LanguageServerCandidateInfo,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .testTag(LanguageServerTags.LSP_CHOICE_DETAILS),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProjectBadge(candidate.displayName, size = 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(candidate.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Label(candidate.licence, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
        }
        Section("Advantages") {
            candidate.strengths.forEach { Bullet(it) { CheckIcon(color = Success) } }
        }
        Section("Limitations") {
            candidate.drawbacks.forEach { Bullet(it) { DashIcon() } }
        }
        Section("On the server machine") {
            Column(
                Modifier.testTag(LanguageServerTags.lspChoiceDetail(candidate.id)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                candidate.details.forEach { line ->
                    Bullet(line, if (candidate.available) IdeColors.Text else IdeColors.Error) {
                        if (candidate.available) CheckIcon(color = Success) else CloseIcon(color = IdeColors.Error)
                    }
                }
            }
        }
        Label(
            "It runs on the server machine and analyses ${choice.languageName} files. " +
                "You can change it later in the Language Servers window.",
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 3,
        )
    }
}

// IntelliJ's titled separator: a title followed by a line.
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(title, color = IdeColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f).height(1.dp).background(IdeColors.Separator))
        }
        content()
    }
}

@Composable
private fun Bullet(text: String, color: Color = IdeColors.Text, icon: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 1.dp)) { icon() }
        Label(text, color = color, fontSize = 13.sp, maxLines = 3)
    }
}

@Composable
private fun DashIcon() {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        val y = size.height / 2
        drawLine(Caution, Offset(size.width * 0.28f, y), Offset(size.width * 0.72f, y), stroke, StrokeCap.Round)
    }
}

@Composable
private fun DialogFooter(state: LanguageServersState, selected: LanguageServerCandidateInfo, use: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(
            if (state.choosing) "Saving the choice…" else "↑↓ to select · Enter to use",
            Modifier.weight(1f),
            color = IdeColors.TextSecondary,
            fontSize = 11.sp,
        )
        IdeButton("Cancel", state::closeChoiceDialog, Modifier.testTag(LanguageServerTags.LSP_CHOICE_CANCEL))
        IdeButton(
            "Use ${selected.displayName}",
            use,
            Modifier.testTag(LanguageServerTags.lspChoiceUse(selected.id)),
            primary = true,
            enabled = selected.available && !state.choosing,
        )
    }
}
