package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.SERVER_LANGUAGES
import org.example.kotlineditor.state.Setting
import org.example.kotlineditor.state.SettingsState
import kotlin.math.roundToInt

private val ChipShape = RoundedCornerShape(4.dp)
private val CardShape = RoundedCornerShape(6.dp)
private val Success = Color(0xFF5FAD65)

/** The editors' font size: smaller, larger, back to the default. */
@Composable
internal fun FontSizeControl(app: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IdeButton("−", { app.settings.zoomEditor(-1f) }, Modifier.testTag(AppTags.SETTINGS_FONT_SMALLER))
        Label(
            "${app.settings.editorFontSize.roundToInt()} pt",
            Modifier.widthIn(min = 44.dp).testTag(AppTags.SETTINGS_FONT_SIZE),
        )
        IdeButton("+", { app.settings.zoomEditor(1f) }, Modifier.testTag(AppTags.SETTINGS_FONT_LARGER))
        IdeButton("Reset", { app.settings.resetEditorZoom() }, Modifier.testTag(AppTags.SETTINGS_FONT_RESET))
    }
}

/** Off, Popup or Inline, the one chosen highlighted. */
@Composable
internal fun InlayHintsControl(app: AppState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in InlayHintsMode.entries) {
            val chosen = app.settings.inlayHintsMode == mode
            Label(
                mode.name,
                Modifier
                    .background(if (chosen) IdeColors.Selection else Color.Transparent, ChipShape)
                    .border(1.dp, IdeColors.Separator, ChipShape)
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                    ) { app.settings.inlayHintsMode = mode }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { selected = chosen }
                    .testTag(AppTags.settingsInlayHints(mode.name))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** A check box and the title of [setting]. */
@Composable
internal fun SettingCheckBox(
    setting: Setting,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val toggle = Modifier.toggleable(checked, interaction, null, enabled, Role.Checkbox, onChange)
    Row(
        toggle.pointerHoverIcon(PointerIcon.Hand).testTag(AppTags.settingToggle(setting.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) IdeColors.Accent else IdeColors.Input, ChipShape)
                .border(1.dp, IdeColors.InputBorder, ChipShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) CheckIcon(color = Color.White)
        }
        Label(setting.title, color = if (enabled) IdeColors.Text else IdeColors.TextSecondary)
    }
}

/** The telemetry consent; turned off by `KOTLIN_EDITOR_TELEMETRY`, it cannot be turned on and says why. */
@Composable
internal fun TelemetryControl(app: AppState) {
    val consent = app.settings.telemetry
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingCheckBox(Setting.Telemetry, consent.enabled, enabled = !consent.forbidden, onChange = consent::choose)
        if (consent.forbidden) {
            Label(
                "Turned off on this machine by KOTLIN_EDITOR_TELEMETRY.",
                Modifier.testTag(AppTags.SETTINGS_TELEMETRY_FORBIDDEN),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/** The servers the server offers for each language, the one used marked, a button to switch to each of the others. */
@Composable
internal fun ServerControl(settings: SettingsState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        settings.serverError?.let {
            Label(
                it,
                Modifier.testTag(AppTags.SETTINGS_SERVERS_ERROR),
                color = IdeColors.Error,
                fontSize = 12.sp,
                maxLines = 3,
            )
        }
        val loaded = SERVER_LANGUAGES.all { settings.serversOf(it) != null }
        if (!loaded) {
            Label(
                "Asking the server…",
                Modifier.testTag(AppTags.SETTINGS_SERVERS_LOADING),
                color = IdeColors.TextSecondary,
            )
        }
        SERVER_LANGUAGES.forEach { language ->
            val choice = settings.serversOf(language)
            if (choice != null && choice.candidates.isNotEmpty()) LanguageServerList(settings, language, choice)
        }
    }
}

@Composable
private fun LanguageServerList(settings: SettingsState, language: String, choice: LanguageServerChoice) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(
            Languages.nameOf(language),
            Modifier.testTag(AppTags.settingsServerLanguage(language)),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        choice.candidates.forEach {
            ServerCard(settings, language, it, isCurrent = it.id == choice.chosen)
        }
    }
}

@Composable
private fun ServerCard(
    settings: SettingsState,
    language: String,
    candidate: LanguageServerCandidateInfo,
    isCurrent: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (isCurrent) IdeColors.Accent else IdeColors.Separator, CardShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(AppTags.settingsServer(candidate.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(candidate.displayName, fontWeight = FontWeight.SemiBold)
            Label(candidate.licence, color = IdeColors.TextSecondary, fontSize = 12.sp)
            val status = if (candidate.available) "Can run on the server" else candidate.details.joinToString(" ")
            Label(status, color = if (candidate.available) Success else IdeColors.Error, fontSize = 11.sp, maxLines = 2)
        }
        when {
            isCurrent -> {
                Label("In use", color = IdeColors.Accent, fontSize = 12.sp)
            }

            settings.switchingTo == candidate.id -> {
                Label(
                    "Switching…",
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            else -> {
                IdeButton(
                    "Use",
                    { settings.useServer(language, candidate.id) },
                    Modifier.testTag(AppTags.settingsServerUse(candidate.id)),
                    enabled = candidate.available && settings.switchingTo == null,
                )
            }
        }
    }
}
