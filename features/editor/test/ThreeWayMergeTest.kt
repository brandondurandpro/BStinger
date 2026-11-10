package org.example.kotlineditor.unit

import org.example.kotlineditor.state.MergeChoice
import org.example.kotlineditor.state.MergeKind
import org.example.kotlineditor.state.MergeState
import org.example.kotlineditor.state.ThreeWayMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The three-way merge behind the merge tab: what resolves by itself, what is a conflict, what the result holds. */
class ThreeWayMergeTest {
    private fun merge(base: List<String>, ours: List<String>, theirs: List<String>): MergeState =
        MergeState(ThreeWayMerge.chunks(base, ours, theirs), "\n")

    private fun kinds(state: MergeState): List<MergeKind> = state.chunks.map { it.kind }

    @Test
    fun `three equal versions are one unchanged stretch`() {
        val lines = listOf("a", "b", "c")
        val state = merge(lines, lines, lines)
        assertEquals(listOf(MergeKind.Unchanged), kinds(state))
        assertEquals(0, state.unresolved)
        assertEquals(lines, state.resultLines())
    }

    @Test
    fun `a change on one side only is taken without asking`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "B", "c"), theirs = base)
        assertEquals(listOf(MergeKind.Unchanged, MergeKind.OursOnly, MergeKind.Unchanged), kinds(state))
        assertEquals(listOf("a", "B", "c"), state.resultLines())
        val other = merge(base, ours = base, theirs = listOf("a", "b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), other.resultLines())
        assertEquals(0, other.unresolved)
    }

    @Test
    fun `changes in different places both land in the result`() {
        val base = listOf("a", "b", "c", "d", "e")
        val state = merge(base, ours = listOf("A", "b", "c", "d", "e"), theirs = listOf("a", "b", "c", "d", "E"))
        assertEquals(0, state.unresolved)
        assertEquals(listOf("A", "b", "c", "d", "E"), state.resultLines())
    }

    @Test
    fun `the same change on both sides is not a conflict`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "X", "c"), theirs = listOf("a", "X", "c"))
        assertEquals(listOf(MergeKind.Unchanged, MergeKind.BothSame, MergeKind.Unchanged), kinds(state))
        assertEquals(listOf("a", "X", "c"), state.resultLines())
    }

    @Test
    fun `different changes of the same line are a conflict that waits for the user`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "ours", "c"), theirs = listOf("a", "theirs", "c"))
        assertEquals(listOf(MergeKind.Unchanged, MergeKind.Conflict, MergeKind.Unchanged), kinds(state))
        assertEquals(1, state.unresolved)
        assertEquals(MergeChoice.Pending, state.choiceOf(1))
        assertEquals(base, state.resultLines(), "an undecided conflict shows the base")
    }

    @Test
    fun `choosing a side or both decides the conflict`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "ours", "c"), theirs = listOf("a", "theirs", "c"))
        state.choose(1, MergeChoice.Ours)
        assertEquals(listOf("a", "ours", "c"), state.resultLines())
        assertEquals(0, state.unresolved)
        state.choose(1, MergeChoice.Theirs)
        assertEquals(listOf("a", "theirs", "c"), state.resultLines())
        state.choose(1, MergeChoice.Both)
        assertEquals(listOf("a", "ours", "theirs", "c"), state.resultLines())
        state.choose(1, MergeChoice.Base)
        assertEquals(base, state.resultLines())
    }

    @Test
    fun `a change taken by itself can be turned back to the base`() {
        val base = listOf("a", "b")
        val state = merge(base, ours = listOf("a", "B"), theirs = base)
        state.choose(1, MergeChoice.Base)
        assertEquals(base, state.resultLines())
    }

    @Test
    fun `a line deleted by one side and edited by the other is a conflict`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "c"), theirs = listOf("a", "B", "c"))
        assertEquals(1, state.unresolved)
        val conflict = state.chunks.firstOrNull { it.kind == MergeKind.Conflict }
        assertNotNull(conflict)
        assertEquals(emptyList(), conflict.ours)
        assertEquals(listOf("B"), conflict.theirs)
    }

    @Test
    fun `two insertions at the same place conflict, and without a base every line is a conflict`() {
        val inserted = merge(listOf("a", "b"), ours = listOf("a", "x", "b"), theirs = listOf("a", "y", "b"))
        assertEquals(1, inserted.unresolved)
        val added = merge(emptyList(), ours = listOf("one"), theirs = listOf("two"))
        assertEquals(1, added.unresolved)
        added.choose(0, MergeChoice.Both)
        assertEquals(listOf("one", "two"), added.resultLines())
    }

    @Test
    fun `a final newline removed by one side is a change of one side`() {
        val base = listOf("a", "")
        val state = merge(base, ours = base, theirs = listOf("a"))
        assertEquals(0, state.unresolved)
        assertEquals(listOf("a"), state.resultLines())
    }

    @Test
    fun `accepting the rest decides only the conflicts still open`() {
        val base = listOf("a", "b", "c", "d", "e")
        val state =
            merge(base, ours = listOf("a", "o1", "c", "o2", "e"), theirs = listOf("a", "t1", "c", "t2", "e"))
        assertEquals(2, state.unresolved)
        state.choose(1, MergeChoice.Theirs)
        state.resolveRemaining(MergeChoice.Ours)
        assertEquals(listOf("a", "t1", "c", "o2", "e"), state.resultLines())
        assertEquals(0, state.unresolved)
    }

    @Test
    fun `the next conflict wraps around and is null once none is left`() {
        val base = listOf("a", "b", "c", "d", "e")
        val state =
            merge(base, ours = listOf("a", "o1", "c", "o2", "e"), theirs = listOf("a", "t1", "c", "t2", "e"))
        val conflicts = state.chunks.indices.filter { state.chunks[it].kind == MergeKind.Conflict }
        assertEquals(conflicts[1], state.nextUnresolved(conflicts[0]))
        assertEquals(conflicts[0], state.nextUnresolved(conflicts[1]))
        state.resolveRemaining(MergeChoice.Ours)
        assertEquals(null, state.nextUnresolved(0))
    }

    @Test
    fun `rows align the three columns stretch by stretch and number each column apart`() {
        val base = listOf("a", "b", "c")
        val state = merge(base, ours = listOf("a", "o1", "o2", "c"), theirs = listOf("a", "t", "c"))
        val rows = state.rows()
        val heading = rows.indexOfFirst { it.heading }
        assertEquals(1, heading)
        val first = rows[heading + 1]
        assertEquals(Triple("o1", "b", "t"), Triple(first.ours, first.result, first.theirs))
        val second = rows[heading + 2]
        assertEquals(Triple("o2", null, null), Triple(second.ours, second.result, second.theirs))
        val last = rows.last()
        assertEquals(Triple(4, 3, 3), Triple(last.oursNumber, last.resultNumber, last.theirsNumber))
    }

    @Test
    fun `a large file merges quickly and keeps every line`() {
        val base = List(20_000) { "line $it" }
        val ours = base.mapIndexed { index, line -> if (index % 1000 == 10) "ours $index" else line }
        val theirs = base.mapIndexed { index, line -> if (index % 1000 == 500) "theirs $index" else line }
        val state = merge(base, ours, theirs)
        assertEquals(0, state.unresolved)
        assertEquals(base.size, state.resultLines().size)
    }

    @Test
    fun `the matching of random versions is an ordered common subsequence as long as the best one`() {
        val random = java.util.Random(7)
        repeat(200) {
            val base = List(random.nextInt(14)) { "l${random.nextInt(5)}" }
            val other = List(random.nextInt(14)) { "l${random.nextInt(5)}" }
            val matches =
                org.example.kotlineditor.state.LineMatching
                    .match(base, other)
            val pairs = matches.withIndex().filter { it.value >= 0 }
            pairs.forEach { assertEquals(base[it.index], other[it.value]) }
            pairs.zipWithNext().forEach { (before, after) -> assertEquals(true, before.value < after.value) }
            assertEquals(longestCommon(base, other), pairs.size, "$base / $other")
        }
    }

    private fun longestCommon(a: List<String>, b: List<String>): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                table[i + 1][j + 1] = if (a[i] == b[j]) table[i][j] + 1 else maxOf(table[i][j + 1], table[i + 1][j])
            }
        }
        return table[a.size][b.size]
    }
}
