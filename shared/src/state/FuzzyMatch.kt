package org.example.kotlineditor.state

/**
 * IntelliJ-like fuzzy matching of names: the query characters must appear in order; contiguous characters, word
 * starts (CamelCase humps, after `_ - .`) and prefixes score higher.
 */
internal object FuzzyMatch {
    private const val CONTIGUOUS = 10
    private const val WORD_START = 8
    private const val ANYWHERE = 1
    private const val SAME_CASE = 1
    private const val STARTS_AT_FIRST_CHAR = 15
    private const val PREFIX = 30
    private const val EXACT_NAME = 50
    private const val SEPARATORS = "_-. "

    /** Score and matched character indices of [query] in [name], or `null` when it does not match. */
    fun match(query: String, name: String): Pair<Int, List<Int>>? {
        val indices = ArrayList<Int>(query.length)
        var score = 0
        var previous = -2
        for (qc in query.filter { it != ' ' }) {
            val found = position(name, qc, previous)
            if (found < 0) return null
            score += characterScore(name, qc, found, previous)
            indices += found
            previous = found
        }
        return score + bonus(query, name, indices) to indices
    }

    // Prefers a word start or the character right after the previous match, else the first occurrence.
    private fun position(name: String, qc: Char, previous: Int): Int {
        val candidates = (maxOf(previous + 1, 0) until name.length).filter { name[it].equals(qc, ignoreCase = true) }
        return candidates.firstOrNull { isWordStart(name, it) || it == previous + 1 } ?: candidates.firstOrNull() ?: -1
    }

    private fun characterScore(name: String, qc: Char, found: Int, previous: Int): Int {
        val placement =
            when {
                found == previous + 1 -> CONTIGUOUS
                isWordStart(name, found) -> WORD_START
                else -> ANYWHERE
            }
        return placement + if (name[found] == qc) SAME_CASE else 0
    }

    private fun bonus(query: String, name: String, indices: List<Int>): Int {
        val first = if (indices.firstOrNull() == 0) STARTS_AT_FIRST_CHAR else 0
        val prefix = if (name.startsWith(query, ignoreCase = true)) PREFIX else 0
        val exact = if (name.substringBeforeLast('.').equals(query, ignoreCase = true)) EXACT_NAME else 0
        return first + prefix + exact
    }

    private fun isWordStart(name: String, i: Int): Boolean {
        if (i == 0) return true
        val hump = name[i].isUpperCase() && !name[i - 1].isUpperCase()
        return hump || name[i - 1] in SEPARATORS
    }
}
