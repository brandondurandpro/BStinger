package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentUsage
import java.util.Locale

private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
private const val PERCENT = 100

/** `24k`, `1.2M`: a number of tokens, short. */
fun tokensText(tokens: Long): String =
    when {
        tokens >= MILLION -> String.format(Locale.ROOT, "%.1fM", tokens.toDouble() / MILLION)
        tokens >= THOUSAND -> "${tokens / THOUSAND}k"
        else -> tokens.toString()
    }

/** `12% · 24k/200k · $0.12`: how full the context window is and what the session cost. */
fun usageText(usage: AgentUsage): String {
    val percent = if (usage.size > 0) usage.used * PERCENT / usage.size else 0
    val parts = mutableListOf("$percent%", "${tokensText(usage.used)}/${tokensText(usage.size)}")
    val cost = usage.cost
    if (cost != null) {
        val symbol = if (usage.currency == "USD" || usage.currency == null) "$" else "${usage.currency} "
        parts.add(String.format(Locale.ROOT, "%s%.2f", symbol, cost))
    }
    return parts.joinToString(" · ")
}
