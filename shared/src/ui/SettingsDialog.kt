package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.DEFAULT_AGENT
import org.example.kotlineditor.state.Setting
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.state.SettingsState

private val Backdrop = Color(0x66000000)
private val DialogShape = IdeShapes.ExtraLarge
private val RowShape = IdeShapes.Large

/**
 * The Settings dialog, like IntelliJ's (Ctrl+Alt+S): a search and the categories on the left, the settings of the
 * category, or those the search finds, on the right. A change applies at once; Escape or a click outside closes.
 */
@Composable
fun SettingsDialog(app: AppState) {
    val settings = app.settings.dialog ?: return
    val search = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { search.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .pointerInput(Unit) { detectTapGestures { app.settings.close() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(860.dp)
                .height(560.dp)
                .background(IdeColors.Panel, DialogShape)
                .border(1.dp, IdeColors.Separator, DialogShape)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
                .closesOnEscape(app)
                .testTag(AppTags.SETTINGS),
        ) {
            SettingsTitle(app)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                CategoryList(settings, search)
                Box(Modifier.width(1.dp).fillMaxHeight().background(IdeColors.Separator))
                SettingsPane(app, settings, Modifier.weight(1f))
            }
        }
    }
}

private fun Modifier.closesOnEscape(app: AppState): Modifier =
    onPreviewKeyEvent { event ->
        val closes = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        if (closes) app.settings.close()
        closes
    }

@Composable
private fun SettingsTitle(app: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("Settings", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        IconButton({ app.settings.close() }, Modifier.testTag(AppTags.SETTINGS_CLOSE)) { CloseIcon() }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

@Composable
private fun CategoryList(settings: SettingsState, search: FocusRequester) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IdeTextField(
            settings.query,
            { settings.query = it },
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .focusRequester(search)
                .testTag(AppTags.SETTINGS_SEARCH),
            placeholder = "Search settings",
            leading = { SearchIcon() },
        )
        val searching = settings.query.isNotBlank()
        for (category in SettingsCategory.entries) {
            CategoryRow(category, chosen = !searching && settings.category == category) { settings.select(category) }
        }
    }
}

// Like IntelliJ's lists: the chosen row highlighted, the hovered one shaded, no ripple.
@Composable
private fun CategoryRow(category: SettingsCategory, chosen: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            chosen -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Label(
        category.title,
        Modifier
            .fillMaxWidth()
            .background(background, RowShape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { selected = chosen }
            .testTag(AppTags.settingsCategory(category.name))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsPane(app: AppState, settings: SettingsState, modifier: Modifier) {
    val shown = settings.shown
    val searching = settings.query.isNotBlank()
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val title = if (searching) "Search results" else settings.category.title
        Label(title, Modifier.testTag(AppTags.SETTINGS_TITLE), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (shown.isEmpty()) {
            Label(
                "No setting matches “${settings.query.trim()}”.",
                Modifier.testTag(AppTags.SETTINGS_EMPTY),
                color = IdeColors.TextSecondary,
            )
        }
        for (setting in shown) SettingRow(app, settings, setting, showCategory = searching)
    }
}

// A setting: its title (its category's name too in search results), what it does, and its control.
@Composable
private fun SettingRow(app: AppState, settings: SettingsState, setting: Setting, showCategory: Boolean) {
    Column(
        Modifier.fillMaxWidth().testTag(AppTags.setting(setting.name)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val title = if (showCategory) "${setting.category.title} › ${setting.title}" else setting.title
        Label(title, fontWeight = FontWeight.SemiBold)
        Label(setting.description, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
        Box(Modifier.padding(top = 4.dp)) { SettingControl(app, settings, setting) }
    }
}

@Composable
private fun SettingControl(app: AppState, settings: SettingsState, setting: Setting) {
    when (setting) {
        Setting.Theme -> {
            ThemeControl(app)
        }

        Setting.FontSize -> {
            FontSizeControl(app)
        }

        Setting.InlayHints -> {
            InlayHintsControl(app)
        }

        Setting.LanguageServers -> {
            ServerControl(settings)
        }

        Setting.AutoSelect -> {
            SettingCheckBox(
                setting,
                app.settings.autoSelectOpenedFile,
            ) { app.settings.autoSelectOpenedFile = it }
        }

        Setting.Telemetry -> {
            TelemetryControl(app)
        }

        Setting.AgentMode -> {
            SettingCheckBox(
                setting,
                app.settings.agentModeEnabled,
            ) { app.settings.enableAgentMode(it) }
        }

        Setting.AgentModeByDefault -> {
            SettingCheckBox(
                setting,
                app.settings.agentModeByDefault,
            ) { app.settings.agentModeByDefault = it }
        }

        Setting.Agent -> {
            RegistryAgentChoice(app)
        }

        Setting.AgentCommand -> {
            AgentCommandField(app)
        }
    }
}

// The agent of the ACP registry; choosing one clears the command line, since a command line wins over it.
@Composable
private fun RegistryAgentChoice(app: AppState) {
    val chosen =
        app.settings.agentRegistry
            .ifBlank { DEFAULT_AGENT }
            .takeIf { app.settings.agentCommand.isBlank() }
    AgentRegistryControl(app.session.services.agents, chosen) { id ->
        app.settings.agentRegistry = id
        app.settings.agentCommand = ""
    }
}

// The command line of the agent; it applies to the next agent started.
@Composable
private fun AgentCommandField(app: AppState) {
    IdeTextField(
        app.settings.agentCommand,
        { app.settings.agentCommand = it },
        Modifier.fillMaxWidth().testTag(AppTags.SETTINGS_AGENT_COMMAND),
        placeholder = "npx -y @agentclientprotocol/claude-agent-acp",
        monospace = true,
    )
}
