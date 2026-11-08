package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.PromptTemplate

// The saved prompts that [filter] finds, then "save the draft as a template" when there is a draft to save.
@Composable
internal fun TemplateItems(agent: AgentState, conversation: Conversation, filter: String, close: () -> Unit) {
    val wanted = filter.trim()
    val shown = agent.templates.filter { it.matches(wanted) }
    for (template in shown) {
        TemplateItem(
            template,
            choose = {
                close()
                conversation.draft = template.text
                conversation.context.focus()
            },
            remove = { agent.removeTemplate(template.name) },
        )
    }
    if (conversation.draft.isNotBlank()) {
        DropdownMenuItem(
            text = { Label("★ Save the prompt as a template") },
            onClick = {
                agent.saveTemplate("", conversation.draft)
                close()
            },
            modifier = Modifier.testTag(AgentTags.AGENT_TEMPLATE_SAVE),
        )
    }
}

private fun PromptTemplate.matches(filter: String): Boolean =
    filter.isEmpty() || name.contains(filter, ignoreCase = true) || text.contains(filter, ignoreCase = true)

@Composable
private fun TemplateItem(template: PromptTemplate, choose: () -> Unit, remove: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("★ ${template.name}")
                    Label(template.text, color = IdeColors.TextSecondary, fontSize = 11.sp, maxLines = 2)
                }
                Label(
                    "×",
                    Modifier.padding(start = 8.dp).clickable(onClick = remove).testTag(AgentTags.agentTemplateRemove(template.name)),
                    color = IdeColors.TextSecondary,
                )
            }
        },
        onClick = choose,
        modifier = Modifier.testTag(AgentTags.agentTemplate(template.name)),
    )
}
