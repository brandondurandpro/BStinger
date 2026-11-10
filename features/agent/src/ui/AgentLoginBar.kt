package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentAuthMethod
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.logIn
import org.example.kotlineditor.state.loginMethods

/** Under the notice of an agent that needs a login: one button per way to log in, listed by the agent. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoginBar(agent: AgentState, conversation: Conversation) {
    var methods by remember { mutableStateOf<List<AgentAuthMethod>>(emptyList()) }
    var running by remember { mutableStateOf<AgentAuthMethod?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(conversation) { methods = agent.loginMethods(conversation) }
    val busy = running
    if (busy != null) {
        Label("Logging in with ${busy.name}…", Modifier.testTag(AgentTags.AGENT_LOGIN_RUNNING), color = IdeColors.TextSecondary, fontSize = 12.sp)
        return
    }
    FlowRow(
        Modifier.padding(top = 6.dp).testTag(AgentTags.AGENT_LOGIN),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (method in methods) {
            val login = {
                running = method
                scope.launch {
                    agent.logIn(conversation, method)
                    running = null
                }
                Unit
            }
            Label(
                method.name,
                Modifier
                    .background(IdeColors.Selection, IdeShapes.Medium)
                    .clickable(onClick = login)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = IdeColors.Text,
                fontSize = 12.sp,
            )
        }
    }
}
