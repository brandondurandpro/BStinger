package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.TEMPLATE_VARIABLES
import org.example.kotlineditor.state.expandTemplate
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.PromptTemplate

private val DIALOG_WIDTH = 560.dp
private val LIST_HEIGHT = 140.dp
private val TEXT_HEIGHT = 160.dp

// "★": the prompt templates, to write, name, keep and put in the prompt.
@Composable
internal fun TemplatesButton(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    Label(
        "★",
        Modifier
            .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(AgentTags.AGENT_TEMPLATES),
        color = IdeColors.TextSecondary,
        fontSize = 13.sp,
    )
    if (open) TemplatesDialog(agent, conversation) { open = false }
}

@Composable
private fun TemplatesDialog(agent: AgentState, conversation: Conversation, close: () -> Unit) {
    // The template being edited: null for a new one.
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(true) }
    val edit = { template: PromptTemplate? ->
        global = template?.global ?: global
        editing = template?.name
        name = template?.name.orEmpty()
        text = template?.text.orEmpty()
    }
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .width(DIALOG_WIDTH)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("Prompt templates", fontWeight = FontWeight.SemiBold)
            TemplateList(agent.templates, editing) { edit(it) }
            IdeTextField(name, { name = it }, Modifier.fillMaxWidth().testTag(AgentTags.AGENT_TEMPLATE_NAME), placeholder = "Name")
            TemplateTextField(text, { text = it }, Modifier.testTag(AgentTags.AGENT_TEMPLATE_TEXT))
            VariableChips { variable -> text += "{$variable}" }
            ScopeChoice(global) { global = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IdeButton("New", { edit(null) }, Modifier.testTag(AgentTags.AGENT_TEMPLATE_NEW))
                IdeButton(
                    "Delete",
                    {
                        editing?.let(agent::removeTemplate)
                        edit(null)
                    },
                    Modifier.testTag(AgentTags.AGENT_TEMPLATE_DELETE),
                    enabled = editing != null,
                )
                Box(Modifier.weight(1f))
                IdeButton(
                    "Save",
                    { if (agent.saveTemplate(editing, name, text, global)) editing = name.trim() },
                    Modifier.testTag(AgentTags.AGENT_TEMPLATE_SAVE),
                    enabled = name.isNotBlank() && text.isNotBlank(),
                )
                IdeButton(
                    "Use",
                    {
                        conversation.draft = agent.expandTemplate(text.trim(), conversation)
                        close()
                        conversation.context.focus()
                    },
                    Modifier.testTag(AgentTags.AGENT_TEMPLATE_USE),
                    primary = true,
                    enabled = text.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun TemplateList(templates: List<PromptTemplate>, editing: String?, choose: (PromptTemplate) -> Unit) {
    if (templates.isEmpty()) {
        Label("No template yet: write a name and a prompt, then Save.", color = IdeColors.TextSecondary, fontSize = 12.sp)
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT).verticalScroll(rememberScrollState())) {
        for (template in templates) {
            val background = if (template.name == editing) IdeColors.Selection else Color.Transparent
            Label(
                "★ ${template.name}" + if (template.global) "" else "  · this project",
                Modifier
                    .fillMaxWidth()
                    .background(background, IdeShapes.Medium)
                    .clickable { choose(template) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(AgentTags.agentTemplate(template.name)),
            )
        }
    }
}

@Composable
private fun TemplateTextField(text: String, change: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value = text,
        onValueChange = change,
        modifier = modifier.fillMaxWidth().height(TEXT_HEIGHT),
        cursorBrush = SolidColor(IdeColors.Caret),
        textStyle = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.Text, fontSize = 13.sp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(IdeColors.Input, IdeShapes.Medium)
                    .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
                    .padding(8.dp),
            ) {
                if (text.isEmpty()) Label("Prompt", color = IdeColors.TextSecondary)
                inner()
            }
        },
    )
}

// Where the template is kept: for every project, or for this one only.
@Composable
private fun ScopeChoice(global: Boolean, choose: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Available in", color = IdeColors.TextSecondary, fontSize = 12.sp)
        ScopeButton("All projects", global, AgentTags.AGENT_TEMPLATE_GLOBAL) { choose(true) }
        ScopeButton("This project", !global, AgentTags.AGENT_TEMPLATE_PROJECT) { choose(false) }
    }
}

@Composable
private fun ScopeButton(text: String, selected: Boolean, tag: String, choose: () -> Unit) {
    val background = if (selected) IdeColors.Selection else Color.Transparent
    Label(
        text,
        Modifier
            .background(background, IdeShapes.Medium)
            .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
            .clickable(onClick = choose)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(tag),
        fontSize = 12.sp,
    )
}

// The variables a template may hold: a click adds one at the end of the prompt; the tooltip says what it stands for.
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun VariableChips(add: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((variable, description) in TEMPLATE_VARIABLES) {
            TooltipArea(tooltip = { Tooltip(description) }) {
                Label(
                    "{$variable}",
                    Modifier
                        .border(1.dp, IdeColors.InputBorder, IdeShapes.ExtraLarge)
                        .clickable { add(variable) }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(AgentTags.agentTemplateVariable(variable)),
                    color = IdeColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
