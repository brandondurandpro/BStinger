package org.example.kotlineditor.state

/**
 * Which lines two versions of a file share, for the three-way merge: a longest common subsequence found by Myers'
 * algorithm, whose cost grows with the number of differences, not with the size of the files, so a file of tens of
 * thousands of lines with changes spread over it matches at once. The lines both ends share are skipped first. A
 * version that differs by more than [MAX_DIFFERENCES] lines is not aligned inside its differing middle: those lines
 * count as changed, which the merge shows as one whole stretch the user decides.
 */
internal object LineMatching {
    /** The most differences (insertions plus deletions) of the middle that are aligned; bounds the memory used. */
    internal const val MAX_DIFFERENCES = 2_500

    /** For each line of [base], the index of the line of [other] it matches, or -1 when it was changed or removed. */
    fun match(base: List<String>, other: List<String>): IntArray {
        val ids = HashMap<String, Int>()
        val baseIds = IntArray(base.size) { ids.getOrPut(base[it]) { ids.size } }
        val otherIds = IntArray(other.size) { ids.getOrPut(other[it]) { ids.size } }
        val matches = IntArray(base.size) { -1 }
        val prefix = sharedPrefix(baseIds, otherIds)
        for (at in 0 until prefix) matches[at] = at
        val suffix = sharedSuffix(baseIds, otherIds, prefix)
        for (back in 1..suffix) matches[baseIds.size - back] = otherIds.size - back
        val middleBase = baseIds.copyOfRange(prefix, baseIds.size - suffix)
        val middleOther = otherIds.copyOfRange(prefix, otherIds.size - suffix)
        alignMiddle(middleBase, middleOther, matches, prefix)
        return matches
    }

    private fun sharedPrefix(base: IntArray, other: IntArray): Int {
        val limit = minOf(base.size, other.size)
        var shared = 0
        while (shared < limit && base[shared] == other[shared]) shared += 1
        return shared
    }

    private fun sharedSuffix(base: IntArray, other: IntArray, prefix: Int): Int {
        val limit = minOf(base.size, other.size) - prefix
        var shared = 0
        while (shared < limit && base[base.size - 1 - shared] == other[other.size - 1 - shared]) shared += 1
        return shared
    }

    // Fills [matches] for the middle, shifted by [offset] on both sides.
    private fun alignMiddle(base: IntArray, other: IntArray, matches: IntArray, offset: Int) {
        if (base.isEmpty() || other.isEmpty()) return
        val trace = shortestEdit(base, other) ?: return
        walkBack(base, other, trace, matches, offset)
    }

    // The state of the furthest-reaching paths before each step of Myers' search, up to the step that reaches the end;
    // `null` when that takes more than [MAX_DIFFERENCES] steps. A step `d` keeps the furthest x of each diagonal
    // `k` of -d..d at index `k + d`.
    private fun shortestEdit(base: IntArray, other: IntArray): List<IntArray>? {
        val offset = MAX_DIFFERENCES + 1
        val furthest = IntArray(2 * offset + 1)
        val trace = ArrayList<IntArray>()
        for (steps in 0..MAX_DIFFERENCES) {
            trace += furthest.copyOfRange(offset - steps, offset + steps + 1)
            for (diagonal in -steps..steps step 2) {
                val x = nextX(furthest, offset, steps, diagonal)
                val reached = slide(base, other, x, x - diagonal)
                furthest[offset + diagonal] = reached
                if (reached >= base.size && reached - diagonal >= other.size) return trace
            }
        }
        return null
    }

    // Where the path to [diagonal] starts its slide in step [steps]: below the diagonal on its left, or to the right
    // of the one on its right, whichever got further.
    private fun nextX(furthest: IntArray, offset: Int, steps: Int, diagonal: Int): Int {
        val left = furthest[offset + diagonal - 1]
        val right = furthest[offset + diagonal + 1]
        return if (comesFromBelow(
                diagonal,
                steps,
                left,
                right,
            )
        ) {
            furthest[offset + diagonal + 1]
        } else {
            furthest[offset + diagonal - 1] + 1
        }
    }

    // Whether the path to [diagonal] in step [steps] comes from the diagonal above it (an insertion) rather than the one
    // below it (a deletion): at the edges there is only one choice, elsewhere the path that got further ([left] is
    // the x reached on the diagonal before, [right] on the one after).
    private fun comesFromBelow(diagonal: Int, steps: Int, left: Int, right: Int): Boolean =
        diagonal == -steps || (diagonal != steps && left < right)

    // Follows the equal lines from (x, y) as far as they go; returns the x reached.
    private fun slide(base: IntArray, other: IntArray, startX: Int, startY: Int): Int {
        var x = startX
        var y = startY
        while (x < base.size && y < other.size && base[x] == other[y]) {
            x += 1
            y += 1
        }
        return x
    }

    // Walks the trace from the end of both versions back to their start: each slide along a diagonal is a run of
    // matching lines.
    private fun walkBack(base: IntArray, other: IntArray, trace: List<IntArray>, matches: IntArray, offset: Int) {
        var x = base.size
        var y = other.size
        for (steps in trace.lastIndex downTo 1) {
            val before = trace[steps]
            val diagonal = x - y
            // An edge diagonal has one neighbour only; the comparison ignores the missing one.
            val left = before.getOrElse(diagonal - 1 + steps) { 0 }
            val right = before.getOrElse(diagonal + 1 + steps) { 0 }
            val fromBelow = comesFromBelow(diagonal, steps, left, right)
            val previousDiagonal = if (fromBelow) diagonal + 1 else diagonal - 1
            val previousX = before[previousDiagonal + steps]
            val previousY = previousX - previousDiagonal
            val movedX = if (fromBelow) previousX else previousX + 1
            val movedY = if (fromBelow) previousY + 1 else previousY
            while (x > movedX && y > movedY) {
                x -= 1
                y -= 1
                matches[offset + x] = offset + y
            }
            x = previousX
            y = previousY
        }
        while (x > 0 && y > 0) {
            x -= 1
            y -= 1
            matches[offset + x] = offset + y
        }
    }
}
