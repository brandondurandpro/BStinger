package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a stretch of a three-way merge is: which sides changed it since their common ancestor. */
enum class MergeKind {
    /** No side changed it. */
    Unchanged,

    /** Only the current branch changed it. */
    OursOnly,

    /** Only the branch being merged in changed it. */
    TheirsOnly,

    /** Both sides changed it the same way. */
    BothSame,

    /** Both sides changed it differently: the user decides. */
    Conflict,
}

/** What the result holds for a stretch. [Pending] is a conflict nobody decided yet: the result shows the base. */
enum class MergeChoice {
    Base,
    Ours,
    Theirs,

    /** The lines of ours, then the lines of theirs. */
    Both,
    Pending,
}

/** One stretch of the three versions, aligned: the same place in the base, in ours and in theirs. */
class MergeChunk(val kind: MergeKind, val base: List<String>, val ours: List<String>, val theirs: List<String>) {
    /** Number of rows the stretch takes in the three columns: the longest of its versions. */
    val height: Int get() = maxOf(base.size, ours.size, theirs.size)

    /** The lines the result holds for this stretch under [choice]. */
    fun resultFor(choice: MergeChoice): List<String> =
        when (choice) {
            MergeChoice.Ours -> ours
            MergeChoice.Theirs -> theirs
            MergeChoice.Both -> ours + theirs
            MergeChoice.Base, MergeChoice.Pending -> base
        }

    /** What the merge picks without asking the user. */
    fun defaultChoice(): MergeChoice =
        when (kind) {
            MergeKind.Unchanged -> MergeChoice.Base
            MergeKind.OursOnly, MergeKind.BothSame -> MergeChoice.Ours
            MergeKind.TheirsOnly -> MergeChoice.Theirs
            MergeKind.Conflict -> MergeChoice.Pending
        }
}

/**
 * The three-way merge of the lines of a file: the stretches both sides left alone or changed alone resolve by
 * themselves, the ones they changed differently are conflicts. A line of the base kept by both sides, at the same
 * place in each, separates two stretches; what lies between two of them is compared as a whole.
 */
object ThreeWayMerge {
    fun chunks(base: List<String>, ours: List<String>, theirs: List<String>): List<MergeChunk> {
        val matchOurs = matching(base, ours)
        val matchTheirs = matching(base, theirs)
        val chunks = ArrayList<MergeChunk>()
        var baseAt = 0
        var oursAt = 0
        var theirsAt = 0
        while (true) {
            val anchor = nextAnchor(matchOurs, matchTheirs, baseAt)
            val anchored = anchor < base.size
            val baseEnd = if (anchored) anchor else base.size
            val oursEnd = if (anchored) matchOurs[anchor] else ours.size
            val theirsEnd = if (anchored) matchTheirs[anchor] else theirs.size
            val baseLines = base.subList(baseAt, baseEnd)
            val oursLines = ours.subList(oursAt, oursEnd)
            val theirsLines = theirs.subList(theirsAt, theirsEnd)
            val between = MergeChunk(kindOf(baseLines, oursLines, theirsLines), baseLines, oursLines, theirsLines)
            if (between.height > 0) addStretch(chunks, between)
            if (!anchored) break
            addStretch(chunks, unchangedLine(base[anchor]))
            baseAt = anchor + 1
            oursAt = oursEnd + 1
            theirsAt = theirsEnd + 1
        }
        return chunks
    }

    // The first line of the base from [from] that both sides kept.
    private fun nextAnchor(matchOurs: IntArray, matchTheirs: IntArray, from: Int): Int {
        var at = from
        while (at < matchOurs.size && (matchOurs[at] < 0 || matchTheirs[at] < 0)) at += 1
        return at
    }

    private fun unchangedLine(line: String): MergeChunk {
        val lines = listOf(line)
        return MergeChunk(MergeKind.Unchanged, lines, lines, lines)
    }

    // Two unchanged stretches in a row are one.
    private fun addStretch(chunks: MutableList<MergeChunk>, chunk: MergeChunk) {
        val last = chunks.lastOrNull()
        val joins = last != null && last.kind == MergeKind.Unchanged && chunk.kind == MergeKind.Unchanged
        if (last != null && joins) {
            chunks[chunks.lastIndex] =
                MergeChunk(
                    MergeKind.Unchanged,
                    last.base + chunk.base,
                    last.ours + chunk.ours,
                    last.theirs + chunk.theirs,
                )
        } else {
            chunks += chunk
        }
    }

    private fun matching(base: List<String>, other: List<String>): IntArray = LineMatching.match(base, other)

    private fun kindOf(base: List<String>, ours: List<String>, theirs: List<String>): MergeKind {
        val oursSame = ours == base
        val theirsSame = theirs == base
        return when {
            oursSame && theirsSame -> MergeKind.Unchanged
            oursSame -> MergeKind.TheirsOnly
            theirsSame -> MergeKind.OursOnly
            ours == theirs -> MergeKind.BothSame
            else -> MergeKind.Conflict
        }
    }
}

/** One row of the three columns: a line of ours, of the result and of theirs (a blank cell is `null`). */
class MergeRow(
    /** The stretch the row belongs to. */
    val chunk: Int,
    /** Whether this row is the heading of a stretch that changed (its title and its choices), without lines. */
    val heading: Boolean,
    val ours: String?,
    val result: String?,
    val theirs: String?,
    /** 1-based line numbers in each column, `-1` for a blank cell. */
    val oursNumber: Int,
    val resultNumber: Int,
    val theirsNumber: Int,
)

/**
 * A three-way merge being resolved: the stretches of [ThreeWayMerge.chunks] and what the result holds for each. The
 * conflicts start undecided; the user chooses a side, both, or the base for any changed stretch, and the result
 * follows. It can be applied once no conflict is left.
 */
@Stable
class MergeState(
    val chunks: List<MergeChunk>,
    /** The line separator the result is written with. */
    val lineSeparator: String,
    /** Names of the versions as the columns show them (the branches). */
    val oursLabel: String = "Ours",
    val theirsLabel: String = "Theirs",
) {
    /** Whether the history of the file on both sides is shown under the columns. */
    var historyVisible by mutableStateOf(false)

    private val choices = mutableStateListOf<MergeChoice>().apply { chunks.forEach { add(it.defaultChoice()) } }

    /** What the result holds for the stretch [index]. */
    fun choiceOf(index: Int): MergeChoice = choices[index]

    /** Number of conflicts nobody decided yet. */
    val unresolved: Int by derivedStateOf { choices.count { it == MergeChoice.Pending } }

    /** Number of stretches both sides changed differently, decided or not. */
    val conflictCount: Int get() = chunks.count { it.kind == MergeKind.Conflict }

    /** Indexes of the stretches that changed on at least one side. */
    val changedChunks: List<Int> = chunks.indices.filter { chunks[it].kind != MergeKind.Unchanged }

    /** Decides what the result holds for the stretch [index]. */
    fun choose(index: Int, choice: MergeChoice) {
        val changed = chunks[index].kind != MergeKind.Unchanged
        if (changed && choice != MergeChoice.Pending) choices[index] = choice
    }

    /** Decides every conflict still undecided in favor of [choice]. */
    fun resolveRemaining(choice: MergeChoice) {
        for (index in chunks.indices) {
            if (choices[index] == MergeChoice.Pending) choices[index] = choice
        }
    }

    /** The first undecided conflict after the stretch [after] (wrapping around), or `null` when none is left. */
    fun nextUnresolved(after: Int): Int? {
        val count = chunks.size
        val candidates = (1..count).map { step -> (after + step).mod(count) }
        return candidates.firstOrNull { choices[it] == MergeChoice.Pending }
    }

    /** The lines of the result, as the choices make them. */
    fun resultLines(): List<String> {
        val lines = ArrayList<String>()
        chunks.forEachIndexed { index, chunk -> lines += chunk.resultFor(choices[index]) }
        return lines
    }

    /** The rows of the three columns, a heading before each stretch that changed. */
    fun rows(): List<MergeRow> {
        val rows = ArrayList<MergeRow>()
        val numbers = intArrayOf(0, 0, 0)
        chunks.forEachIndexed { index, chunk ->
            if (chunk.kind != MergeKind.Unchanged) rows += headingRow(index)
            appendLines(rows, index, chunk, chunk.resultFor(choices[index]), numbers)
        }
        return rows
    }

    private fun headingRow(chunk: Int) = MergeRow(chunk, true, null, null, null, -1, -1, -1)

    private fun appendLines(
        rows: MutableList<MergeRow>,
        index: Int,
        chunk: MergeChunk,
        result: List<String>,
        numbers: IntArray,
    ) {
        val height = maxOf(chunk.ours.size, chunk.theirs.size, result.size)
        for (at in 0 until height) {
            val ours = chunk.ours.getOrNull(at)
            val theirs = chunk.theirs.getOrNull(at)
            val resulting = result.getOrNull(at)
            val oursNumber = if (ours == null) -1 else ++numbers[OURS]
            val resultNumber = if (resulting == null) -1 else ++numbers[RESULT]
            val theirsNumber = if (theirs == null) -1 else ++numbers[THEIRS]
            rows += MergeRow(index, false, ours, resulting, theirs, oursNumber, resultNumber, theirsNumber)
        }
    }

    private companion object {
        const val OURS = 0
        const val RESULT = 1
        const val THEIRS = 2
    }
}
