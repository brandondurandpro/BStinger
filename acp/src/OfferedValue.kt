package org.example.kotlineditor.acp

import org.example.kotlineditor.model.AgentOptionValue

/**
 * The row of [offered] that [wanted] stands for, as the team editor of the IDE reads a saved model or effort: its name
 * or its value, case ignored, then with the same letters and digits (`opus 5.5` for `Opus-5.5`). Never a row that
 * merely contains it (`high` is not `xhigh`); null when nothing matches, and the agent keeps its default.
 */
internal fun offeredValue(offered: List<AgentOptionValue>, wanted: String): AgentOptionValue? {
    val key = squashed(wanted)
    val exact = offered.firstOrNull { it.name.equals(wanted, ignoreCase = true) || it.value.equals(wanted, true) }
    val loose = offered.firstOrNull { squashed(it.name) == key || squashed(it.value) == key }
    return if (key.isEmpty()) null else exact ?: loose
}

private fun squashed(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
