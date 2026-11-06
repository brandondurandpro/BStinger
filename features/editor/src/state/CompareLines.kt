package org.example.kotlineditor.state

import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine

/**
 * The line diff behind the conflict banner's Compare: the lines of the file on the disk against the lines of the
 * editor. It runs on the client (the editor's content never went to the server) and produces the [DiffLine] model
 * the Git diff views already render, so the Compare is shown exactly as they show their diffs.
 *
 * The unchanged common prefix and suffix of the two contents come out as shared lines around a middle, the middle
 * is resolved by a dynamic-programming table of at most [MAX_TABLE_CELLS] cells. A middle that would not fit (a
 * document rewritten throughout, thousands of lines on both sides) is not aligned line by line: it is shown as one
 * whole difference, every disk line removed and every editor line added. The user still sees what each side holds,
 * without the table overrunning the memory of a 32 MB file, or the open of the compare taking longer than a moment.
 */
internal object CompareLines {
    /** A table above this many cells weighs more than the diff it holds (one `int` per cell, per compare). */
    internal const val MAX_TABLE_CELLS = 2_000_000

    /** The differences between what the [disk] holds and what the [editor] holds, in diff order (old, then new). */
    fun diff(disk: List<String>, editor: List<String>): List<DiffLine> {
        val ids = LineIds()
        val diskIds = ids.ids(disk)
        val editorIds = ids.ids(editor)
        val prefix = sharedPrefix(diskIds, editorIds)
        val suffix = sharedSuffix(diskIds, editorIds, prefix)
        val operations = ArrayList<Op>(disk.size + editor.size)
        appendRange(diskIds, 0, prefix, OpKind.Shared, operations)
        appendMiddle(diskIds, editorIds, diskStart = prefix, editorStart = prefix, suffix = suffix, operations)
        appendRange(diskIds, diskIds.size - suffix, diskIds.size, OpKind.Shared, operations)
        return toDiffLines(operations, ids)
    }

    // How many leading lines the two files hold in common (shown as context, the middle comes after them).
    private fun sharedPrefix(diskIds: IntArray, editorIds: IntArray): Int {
        var shared = 0
        while (sharedFront(diskIds, editorIds, shared)) shared += 1
        return shared
    }

    // How many trailing lines the two files hold in common, before reaching the [prefix] the front already took.
    private fun sharedSuffix(diskIds: IntArray, editorIds: IntArray, prefix: Int): Int {
        var shared = 0
        while (sharedBack(diskIds, editorIds, prefix, shared)) shared += 1
        return shared
    }

    // Whether the next forward line exists on both sides and holds the same text.
    private fun sharedFront(diskIds: IntArray, editorIds: IntArray, at: Int): Boolean {
        val onBoth = at < diskIds.size && at < editorIds.size
        return onBoth && diskIds[at] == editorIds[at]
    }

    // Whether the line [shared] positions back from the end keeps matching, without running over the [prefix].
    private fun sharedBack(diskIds: IntArray, editorIds: IntArray, prefix: Int, shared: Int): Boolean {
        val insideDisk = shared <= diskIds.size - prefix - 1
        val insideEditor = shared <= editorIds.size - prefix - 1
        val bothInside = insideDisk && insideEditor
        val sameText = bothInside && diskIds[diskIds.size - shared - 1] == editorIds[editorIds.size - shared - 1]
        return sameText
    }

    // The middle between the shared ends: an empty side is all added or all removed, the rest is either an exact
    // longest-common-subsequence walk through its table, or a whole difference when the table would not hold it.
    private fun appendMiddle(
        diskIds: IntArray,
        editorIds: IntArray,
        diskStart: Int,
        editorStart: Int,
        suffix: Int,
        operations: MutableList<Op>,
    ) {
        val disk = Side(diskIds, diskStart, diskIds.size - suffix)
        val editor = Side(editorIds, editorStart, editorIds.size - suffix)
        // Whether both middles hold a table under the cap (one `int` per cell) resolves this middle exactly.
        val fitsTable = (disk.count + 1L) * (editor.count + 1L) <= MAX_TABLE_CELLS
        when {
            disk.count == 0 -> {
                appendRange(editor.ids, editor.first, editor.last, OpKind.Added, operations)
            }

            editor.count == 0 -> {
                appendRange(disk.ids, disk.first, disk.last, OpKind.Removed, operations)
            }

            fitsTable -> {
                operations.addAll(aligned(disk, editor))
            }

            else -> {
                appendRange(disk.ids, disk.first, disk.last, OpKind.Removed, operations)
                appendRange(editor.ids, editor.first, editor.last, OpKind.Added, operations)
            }
        }
    }

    // The exact edit the two middles are from each other: the LCS table of every prefix pair, walked back from its
    // end. A change block is the removals of the old side followed by the additions of the new one, as `git` shows.
    private fun aligned(disk: Side, editor: Side): List<Op> {
        val width = editor.count + 1
        val table = tableOf(disk, editor, width)
        return walked(disk, editor, table, width)
    }

    // One side of the comparison (the disk's, or the editor's) between its shared ends: the span's first line is
    // included, its last excluded.
    private class Side(val ids: IntArray, val first: Int, val last: Int) {
        val count: Int get() = last - first
    }

    // Fills the longest-common-subsequence table of [disk] and [editor], [width] columns wide.
    private fun tableOf(disk: Side, editor: Side, width: Int): IntArray {
        val table = IntArray(width * (disk.count + 1))
        for (diskAt in 0 until disk.count) {
            val row = (diskAt + 1) * width
            val rowBefore = diskAt * width
            for (editorAt in 0 until editor.count) {
                val sameLine = disk.ids[disk.first + diskAt] == editor.ids[editor.first + editorAt]
                table[row + editorAt + 1] =
                    if (sameLine) {
                        table[rowBefore + editorAt] + 1
                    } else {
                        maxOf(table[rowBefore + editorAt + 1], table[row + editorAt])
                    }
            }
        }
        return table
    }

    // Walks the table back from its end: the last lines of the two middles decide what each position is, the same
    // line on both sides is shared, and the longer neighbourhood gives this side's removal or its addition. The
    // steps come out in walk order; a change block of the reversal is the removals of the disk and then the
    // additions of the editor, as a diff shows it.
    private fun walked(disk: Side, editor: Side, table: IntArray, width: Int): List<Op> {
        val steps = ArrayList<Op>(maxOf(disk.count, editor.count))
        var diskAt = disk.count
        var editorAt = editor.count
        while (diskAt > 0 && editorAt > 0) {
            val matched = disk.ids[disk.first + diskAt - 1] == editor.ids[editor.first + editorAt - 1]
            // A tie takes the addition first, so the reversed steps open the block with its removals.
            val preferAddition =
                table[diskAt * width + editorAt - 1] >= table[(diskAt - 1) * width + editorAt]
            when {
                matched -> {
                    diskAt -= 1
                    editorAt -= 1
                    steps += Op(OpKind.Shared, disk.ids[disk.first + diskAt])
                }

                preferAddition -> {
                    editorAt -= 1
                    steps += Op(OpKind.Added, editor.ids[editor.first + editorAt])
                }

                else -> {
                    diskAt -= 1
                    steps += Op(OpKind.Removed, disk.ids[disk.first + diskAt])
                }
            }
        }
        while (editorAt > 0) {
            editorAt -= 1
            steps += Op(OpKind.Added, editor.ids[editor.first + editorAt])
        }
        while (diskAt > 0) {
            diskAt -= 1
            steps += Op(OpKind.Removed, disk.ids[disk.first + diskAt])
        }
        steps.reverse()
        return steps
    }

    private fun appendRange(ids: IntArray, first: Int, last: Int, kind: OpKind, operations: MutableList<Op>) {
        for (at in first until last) operations += Op(kind, ids[at])
    }

    // The operations carry line ids; walking them in order assigns the 1-based numbers both sides of a diff row show.
    private fun toDiffLines(operations: List<Op>, ids: LineIds): List<DiffLine> {
        val lines = ArrayList<DiffLine>(operations.size)
        var newNumber = 0
        var oldNumber = 0
        for (operation in operations) {
            when (operation.kind) {
                OpKind.Shared -> {
                    oldNumber += 1
                    newNumber += 1
                    lines += DiffLine(DiffKind.Context, oldNumber, newNumber, ids.textFor(operation.id))
                }

                OpKind.Removed -> {
                    oldNumber += 1
                    lines += DiffLine(DiffKind.Removed, oldNumber, -1, ids.textFor(operation.id))
                }

                OpKind.Added -> {
                    newNumber += 1
                    lines += DiffLine(DiffKind.Added, -1, newNumber, ids.textFor(operation.id))
                }
            }
        }
        return lines
    }

    /** One line of the compared contents: a removal from the disk, an addition of the editor, or a shared one. */
    private enum class OpKind {
        Shared,

        Removed,

        Added,
    }

    private class Op(val kind: OpKind, val id: Int)

    // Lines compared by their text: every distinct text gets one small id shared by both sides, so the table and
    // the steps hold ints, not strings.
    private class LineIds {
        private val byText = HashMap<String, Int>()
        private val texts = ArrayList<String>()

        fun ids(lines: List<String>): IntArray {
            val assigned = IntArray(lines.size)
            for (index in lines.indices) assigned[index] = idOf(lines[index])
            return assigned
        }

        fun textFor(id: Int): String = texts[id]

        private fun idOf(text: String): Int {
            val known = byText[text]
            val fresh = known == null
            val id = known ?: texts.size
            if (fresh) {
                byText[text] = id
                texts += text
            }
            return id
        }
    }
}
