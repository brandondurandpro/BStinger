package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CatalogSkill
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.keyOf

// The skill catalog: the skills of popular repositories, which the server downloads and installs on request.

// The share of the window the dialog takes, in width and in height.
private const val WINDOW_SHARE = 0.8f

// Room kept at the right of the list for its scrollbar, which must not hide the buttons.
private val SCROLLBAR_ROOM = 14.dp

/** "Skill catalog": opens the window of the skills to install. Beside the button of the ready-made team. */
@Composable
internal fun SkillCatalogButton(agent: AgentState) {
    var open by remember { mutableStateOf(false) }
    IdeButton("Skill catalog", { open = true }, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_SKILL_CATALOG))
    if (open) SkillCatalogDialog(agent) { open = false }
}

@Composable
private fun SkillCatalogDialog(agent: AgentState, close: () -> Unit) {
    val catalog = agent.teams.catalog
    val choices = agent.project.services.agents
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(choices) { if (!catalog.loaded) catalog.load(choices, refresh = false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            val modifier = Modifier.size(maxWidth * WINDOW_SHARE, maxHeight * WINDOW_SHARE)
            Column(
                modifier
                    .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                    .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                    .testTag(AgentTags.AGENT_CATALOG_DIALOG)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CatalogHeader(catalog.loading, refresh = { scope.launch { catalog.load(choices, refresh = true) } }, close)
                IdeTextField(
                    filter,
                    { filter = it },
                    Modifier.fillMaxWidth().padding(end = 6.dp).testTag(AgentTags.AGENT_CATALOG_FILTER),
                    placeholder = "Filter the skills",
                    leading = { SearchIcon() },
                )
                CatalogBody(agent, filter, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CatalogHeader(loading: Boolean, refresh: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Label("Skill catalog", fontWeight = FontWeight.SemiBold)
            Label(
                "Popular skills from GitHub, copied by the server into the data directory of the IDE, with their licence.",
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
        IdeButton("Refresh", refresh, Modifier.testTag(AgentTags.AGENT_CATALOG_REFRESH), enabled = !loading)
        IconButton(close, Modifier.testTag(AgentTags.AGENT_CATALOG_CLOSE)) {
            Label("✕", color = IdeColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

// One line of the list: the header of a repository, the reason it could not be read, or one of its skills.
private sealed interface CatalogRow {
    val key: String

    class Source(val source: String, val count: Int, val owner: Int, val folded: Boolean) : CatalogRow {
        override val key = "source:$source"
    }

    class Failed(val source: String, val reason: String) : CatalogRow {
        override val key = "failed:$source"
    }

    class Skill(val skill: CatalogSkill, val owner: Int) : CatalogRow {
        override val key = keyOf(skill)
    }
}

private fun CatalogSkill.matches(filter: String): Boolean {
    val words = filter.trim().split(' ').filter { it.isNotEmpty() }
    val text = "$name $description $path $source"
    return words.all { text.contains(it, ignoreCase = true) }
}

// The rows of the catalog: per repository, its failure, its header and its skills that match the filter; a repository
// with none is not shown. The skills of a folded repository are not, unless a filter looks for them.
private fun rowsOf(skills: List<CatalogSkill>, failures: Map<String, String>, filter: String, folded: Set<String>): List<CatalogRow> {
    val rows = ArrayList<CatalogRow>()
    val matching = skills.filter { it.matches(filter) }.groupBy { it.source }
    val sources = (skills.map { it.source } + failures.keys).distinct()
    val searching = filter.isNotBlank()
    for ((owner, source) in sources.withIndex()) {
        val failure = failures[source]
        val own = matching[source].orEmpty()
        val hidden = source in folded && !searching
        if (failure != null) rows.add(CatalogRow.Failed(source, failure))
        if (own.isNotEmpty()) rows.add(CatalogRow.Source(source, own.size, owner, hidden))
        if (!hidden) own.forEach { rows.add(CatalogRow.Skill(it, owner)) }
    }
    return rows
}

@Composable
private fun CatalogBody(agent: AgentState, filter: String, modifier: Modifier) {
    val catalog = agent.teams.catalog
    val failure = catalog.failure
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    // The rows follow what the catalog, the filter and the folded repositories say: recomputed when they change, not on every frame.
    val rows =
        remember(catalog, filter) {
            derivedStateOf { rowsOf(catalog.skills.toList(), catalog.sourceFailures.toMap(), filter, folded.keys.toSet()) }
        }
    Box(modifier.fillMaxWidth()) {
        when {
            failure != null && !catalog.loaded -> CatalogNote("The catalog could not be read: $failure", AgentTags.AGENT_CATALOG_ERROR, IdeColors.Error)
            catalog.loading && !catalog.loaded -> CatalogNote("Downloading the repositories…", AgentTags.AGENT_CATALOG_LOADING, IdeColors.TextSecondary)
            catalog.loaded && rows.value.isEmpty() -> CatalogNote("No skill found.", AgentTags.AGENT_CATALOG_EMPTY, IdeColors.TextSecondary)
            else -> CatalogList(agent, rows.value, folded)
        }
    }
}

@Composable
private fun CatalogNote(text: String, tag: String, color: Color) {
    Label(text, Modifier.padding(vertical = 8.dp).testTag(tag), color = color, fontSize = 12.sp, maxLines = 4)
}

// A lazy list: thousands of skills compose only the rows shown. Its scrollbar is at its right edge.
@Composable
private fun CatalogList(agent: AgentState, rows: List<CatalogRow>, folded: MutableMap<String, Boolean>) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag(AgentTags.AGENT_CATALOG_LIST), state = state, contentPadding = PaddingValues(end = SCROLLBAR_ROOM)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is CatalogRow.Source -> SourceHeader(row) { if (folded.remove(row.source) == null) folded[row.source] = true }
                    is CatalogRow.Failed -> SourceFailure(row)
                    is CatalogRow.Skill -> SkillRow(agent, row.skill, row.owner)
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(AgentTags.AGENT_CATALOG_SCROLLBAR),
        )
    }
}

@Composable
private fun SourceHeader(row: CatalogRow.Source, onToggle: () -> Unit) {
    val source = row.source
    OwnerHeader("$source (${row.count})", ownerColor(row.owner), row.folded, AgentTags.agentCatalogSource(source), AgentTags.agentCatalogSourceToggle(source), onToggle)
}

@Composable
private fun SourceFailure(row: CatalogRow.Failed) {
    Label(
        "${row.source} could not be read: ${row.reason}",
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).testTag(AgentTags.agentCatalogSourceError(row.source)),
        color = IdeColors.Error,
        fontSize = 12.sp,
        maxLines = 3,
    )
}

@Composable
private fun SkillRow(agent: AgentState, skill: CatalogSkill, owner: Int) {
    val catalog = agent.teams.catalog
    val choices = agent.project.services.agents
    val scope = rememberCoroutineScope()
    val key = keyOf(skill)
    val installing = catalog.installing[key] == true
    val failure = catalog.installFailures[key]
    val stripe = Modifier.ownerStripe(ownerColor(owner))
    Row(
        Modifier.fillMaxWidth().then(stripe).padding(vertical = 4.dp, horizontal = 10.dp).testTag(AgentTags.agentCatalogSkill(skill.source, skill.path)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Label(skill.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (skill.description.isNotBlank()) {
                Label(skill.description, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
            if (failure != null) {
                val tag = AgentTags.agentCatalogInstallError(skill.source, skill.path)
                Label("Not installed: $failure", Modifier.testTag(tag), color = IdeColors.Error, fontSize = 12.sp, maxLines = 3)
            }
        }
        if (skill.installed) {
            Label("Installed", Modifier.testTag(AgentTags.agentCatalogInstalled(skill.source, skill.path)), color = IdeColors.GitAdded, fontSize = 12.sp)
        }
        val text = if (installing) "Installing…" else if (skill.installed) "Update" else "Install"
        IdeButton(
            text,
            { scope.launch { agent.teams.installFromCatalog(choices, skill) } },
            Modifier.testTag(AgentTags.agentCatalogInstall(skill.source, skill.path)),
            primary = !skill.installed,
            enabled = !installing,
        )
    }
}
