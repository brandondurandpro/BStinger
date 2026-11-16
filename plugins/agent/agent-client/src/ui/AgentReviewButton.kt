package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.reviewManually

/** "Review" in the card of the changes: opens the review tab, led by hand, of the files [conversation] changed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewButton(agent: AgentState, conversation: Conversation, modifier: Modifier = Modifier, asButton: Boolean = false) {
    val review = { agent.reviewManually(conversation) }
    TooltipArea(tooltip = { Tooltip("Review the changed files, one by one") }, modifier = modifier) {
        if (asButton) {
            IdeButton("Review", review, Modifier.testTag(AgentTags.AGENT_REVIEW), compact = true)
        } else {
            Label(
                "Review",
                Modifier
                    .background(Color.Transparent, IdeShapes.Medium)
                    .clickable(onClick = review)
                    .testTag(AgentTags.AGENT_REVIEW)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}
