package org.example.kotlineditor.git.server

import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitParsersTest {
    private fun fields(vararg values: String) = values.joinToString("\u0000")

    @Test
    fun `a branch line gives its upstream and how far ahead and behind it is`() {
        val branch = parseBranch(fields("main", "*", "origin/main", "[ahead 2, behind 3]"))

        val expected = GitBranchInfo("main", current = true, upstream = "origin/main", ahead = 2, behind = 3)
        assertEquals(expected, branch)
    }

    @Test
    fun `a branch without upstream or with a gone one`() {
        val local = parseBranch(fields("feature", " ", "", ""))
        val gone = parseBranch(fields("old", " ", "origin/old", "[gone]"))

        assertEquals(GitBranchInfo("feature", current = false, upstream = null, ahead = 0, behind = 0), local)
        assertEquals(true, gone?.upstreamGone)
        assertEquals(0, gone?.ahead)
    }

    @Test
    fun `a truncated branch line is skipped`() {
        assertNull(parseBranch(fields("main", "*")))
        assertNull(parseBranch(fields("", "*", "", "")))
    }

    @Test
    fun `commits keep their refs, parents and a subject holding a NUL`() {
        val first = fields("abc", "p1 p2", "Ann", "1700000000", "HEAD -> main, origin/main, tag: v1", "Merge", "rest")
        val second = "\n" + fields("def", "", "Bob", "x", "", "Initial")
        val commits = parseCommits(first + COMMIT_RECORD_SEPARATOR + second + COMMIT_RECORD_SEPARATOR + "\n")

        assertEquals(listOf("abc", "def"), commits.map { it.hash })
        val merge = commits.firstOrNull()
        assertEquals(listOf("p1", "p2"), merge?.parents)
        assertEquals(listOf("main", "origin/main", "tag: v1"), merge?.refs)
        assertEquals("Merge\u0000rest", merge?.subject)
        val initial = commits.lastOrNull()
        assertEquals(emptyList(), initial?.parents)
        assertEquals(0L, initial?.time)
    }

    @Test
    fun `name status reads renames, copies and plain changes`() {
        val raw =
            fields("M", "a.kt", "R100", "old.kt", "new.kt", "A", "b c.kt", "D", "gone.kt", "C75", "s.kt", "t.kt", "")

        val expected =
            listOf(
                GitCommitFile("a.kt", GitFileStatus.Modified),
                GitCommitFile("new.kt", GitFileStatus.Renamed, oldPath = "old.kt"),
                GitCommitFile("b c.kt", GitFileStatus.Added),
                GitCommitFile("gone.kt", GitFileStatus.Deleted),
                GitCommitFile("t.kt", GitFileStatus.Renamed, oldPath = "s.kt"),
            )
        assertEquals(expected, parseNameStatus(raw))
    }

    @Test
    fun `name status stops at a status without its path`() {
        assertEquals(listOf(GitCommitFile("a.kt", GitFileStatus.Added)), parseNameStatus(fields("A", "a.kt", "M")))
        assertEquals(emptyList(), parseNameStatus(""))
    }

    @Test
    fun `status entries keep the old path of a rename and line breaks in paths`() {
        val output = fields("R  new.kt", "old.kt", "?? line\nbreak.kt", " M é à.kt", "UU conflict.kt", "")

        val entries = parseStatusEntries(output.lines())

        assertEquals(listOf("new.kt", "line\nbreak.kt", "é à.kt", "conflict.kt"), entries.map { it.path })
        assertEquals("old.kt", entries.firstOrNull()?.oldPath)
        val statuses = entries.mapNotNull(::statusOf).toMap()
        val expected =
            mapOf(
                "new.kt" to GitFileStatus.Renamed,
                "line\nbreak.kt" to GitFileStatus.Untracked,
                "é à.kt" to GitFileStatus.Modified,
                "conflict.kt" to GitFileStatus.Conflicted,
            )
        assertEquals(expected, statuses)
    }

    @Test
    fun `every unmerged pair of letters is a conflict, other pairs are not`() {
        val unmerged = listOf("UU", "AA", "DD", "AU", "UA", "DU", "UD")
        unmerged.forEach { code ->
            assertEquals(GitFileStatus.Conflicted, statusOf(StatusEntry(code, "f.kt", null))?.second, code)
        }
        assertEquals(GitFileStatus.Added, statusOf(StatusEntry("A ", "f.kt", null))?.second)
        assertEquals(GitFileStatus.Deleted, statusOf(StatusEntry(" D", "f.kt", null))?.second)
    }
}
