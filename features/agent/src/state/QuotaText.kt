package org.example.kotlineditor.state

// The quota of the plan of an agent is no part of ACP: some agents say it in the answer of a command. The IDE reads
// those lines and keeps the latest:
//   `/status` of Codex:  `Weekly limit: 100% left (resets 13:01 on 8 déc.)`
//   `/usage` of Claude:  `5-hour limit — 28% · Resets Nov 8, 2:09 PM GMT+1`

private val LEFT_LINE =
    Regex("""^\s*([^:\n]*limit[^:\n]*):\s*(\d+)\s*%\s*left\s*(.*)$""", RegexOption.IGNORE_CASE)

private val USED_LINE =
    Regex("""^\s*(.+?)\s+[—–-]\s+(\d+)\s*%\s*·\s*(Resets.*)$""", RegexOption.IGNORE_CASE)

private val QUOTA_LABEL = Regex("""limit|weekly|monthly|daily|hour""", RegexOption.IGNORE_CASE)

/** The commands that tell the quota, in the order they are asked for. */
val QUOTA_COMMANDS = listOf("usage", "status")

private fun joined(label: String, percent: String, kind: String, rest: String): String {
    val head = "$label: $percent% $kind"
    return listOf(head, rest.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The lines of [text] that give the quota: `Weekly limit: 100% left · (resets 13:01)`, `5-hour limit: 28% used · …`. */
fun quotaLinesOf(text: String): List<String> =
    text.lineSequence().mapNotNull { line -> quotaLineOf(line) }.toList()

private fun quotaLineOf(line: String): String? {
    val left = LEFT_LINE.matchEntire(line)
    if (left != null) return joined(left.groupValues[1].trim(), left.groupValues[2], "left", left.groupValues[3])
    val used = USED_LINE.matchEntire(line)
    val label = used?.groupValues?.get(1)?.trim()
    if (used == null || label == null || !QUOTA_LABEL.containsMatchIn(label)) return null
    return joined(label, used.groupValues[2], "used", used.groupValues[3])
}
