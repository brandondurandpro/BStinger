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

private const val FULL = 100

/** One limit of the plan of an agent: [percent] of it is [remaining] (left) or used, until [reset] when it says. */
data class QuotaLimit(val label: String, val percent: Int, val remaining: Boolean, val reset: String?) {
    /** The share of the limit already used, from 0 to 100. */
    val used: Int get() = if (remaining) FULL - percent else percent

    /** `28% used`, `100% left`. */
    val text: String get() = "$percent% ${if (remaining) "left" else "used"}"
}

/** The limits [text] gives, one per line such as `Weekly limit: 100% left (resets 13:01)`; none for any other text. */
fun quotaLimitsOf(text: String): List<QuotaLimit> = text.lineSequence().mapNotNull { quotaLimitOf(it) }.toList()

private fun quotaLimitOf(line: String): QuotaLimit? {
    val left = LEFT_LINE.matchEntire(line)
    if (left != null) return limit(left.groupValues[1], left.groupValues[2], remaining = true, left.groupValues[3])
    val used = USED_LINE.matchEntire(line)
    val label = used?.groupValues?.get(1).orEmpty()
    if (used == null || !QUOTA_LABEL.containsMatchIn(label)) return null
    return limit(label, used.groupValues[2], remaining = false, used.groupValues[3])
}

private fun limit(label: String, percent: String, remaining: Boolean, rest: String): QuotaLimit? {
    val value = percent.toIntOrNull()?.coerceIn(0, FULL) ?: return null
    val reset = rest.trim().removeSurrounding("(", ")").trim().ifEmpty { null }
    return QuotaLimit(label.trim(), value, remaining, reset)
}

/** The commands that tell the quota, in the order they are asked for. */
val QUOTA_COMMANDS = listOf("usage", "status")
