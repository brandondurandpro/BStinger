package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.model.AgentUsage
import org.example.kotlineditor.state.QuotaLimit
import org.example.kotlineditor.state.tokensText
import java.util.Locale

/** Width of the quota card, and the room it takes beside the conversation (its margins included). */
internal val QUOTA_CARD_WIDTH = 188.dp
internal val QUOTA_CARD_ROOM: Dp = QUOTA_CARD_WIDTH + 28.dp

/** The narrowest conversation that shows the card; narrower, the limits stay in the bar under the transcript. */
internal val QUOTA_CARD_MIN_WIDTH = 480.dp

private const val FULL = 100
private const val WARNING_FROM = 60
private const val DANGER_FROM = 85
private val CARD_BAR_HEIGHT = 6.dp

// The limits of the plan of the agent, in a card at the top right of the conversation: one bar for each.
@Composable
internal fun QuotaCard(conversation: Conversation, modifier: Modifier) {
    val usage = conversation.usage
    if (conversation.quota.isEmpty() && usage == null && !conversation.started) return
    Column(
        modifier
            .width(QUOTA_CARD_WIDTH)
            .background(IdeColors.Panel, IdeShapes.ExtraLarge)
            .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
            .padding(12.dp)
            .testTag(AgentTags.AGENT_QUOTA_CARD),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (conversation.quota.isNotEmpty()) {
            Label("Quota", color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            conversation.quota.forEachIndexed { index, limit -> CardLimit(limit, Modifier.testTag(AgentTags.agentQuotaLimit(index))) }
        }
        if (usage != null) {
            Label("Conversation", color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            ContextFigure(usage, Modifier.testTag(AgentTags.AGENT_CARD_CONTEXT))
            conversation.costKnown?.let { cost ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("Cost", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Label(costText(cost, usage.currency), Modifier.testTag(AgentTags.AGENT_CARD_COST), fontSize = 12.sp)
                }
            }
        }
        StatsLink(conversation)
    }
}

// The context window: how full it is, as a bar, and in tokens.
@Composable
private fun ContextFigure(usage: AgentUsage, modifier: Modifier) {
    val percent = if (usage.size > 0) (usage.used * FULL / usage.size).toInt() else 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("Context", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth().height(CARD_BAR_HEIGHT).background(IdeColors.Separator, IdeShapes.Small)) {
            val filled = (percent / FULL.toFloat()).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(cardColorOf(percent), IdeShapes.Small))
        }
        Label("$percent% · ${tokensText(usage.used)}/${tokensText(usage.size)}", color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}

private fun costText(cost: Double, currency: String?): String {
    val symbol = if (currency == null || currency == "USD") "$" else "$currency "
    return String.format(Locale.ROOT, "%s%.2f", symbol, cost)
}

@Composable
private fun CardLimit(limit: QuotaLimit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(limit.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth().height(CARD_BAR_HEIGHT).background(IdeColors.Separator, IdeShapes.Small)) {
            val filled = limit.used / FULL.toFloat()
            Box(Modifier.fillMaxHeight().fillMaxWidth(filled.coerceIn(0f, 1f)).background(cardColorOf(limit.used), IdeShapes.Small))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(limit.text, color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        limit.reset?.let { Label("Resets $it", color = IdeColors.TextSecondary, fontSize = 11.sp) }
    }
}

private fun cardColorOf(used: Int): Color =
    when {
        used >= DANGER_FROM -> IdeColors.Error
        used >= WARNING_FROM -> IdeColors.ProblemWarning
        else -> IdeColors.GitAdded
    }
