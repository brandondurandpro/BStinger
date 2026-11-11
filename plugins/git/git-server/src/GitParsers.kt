package org.example.kotlineditor.git.server

import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus

// Parsers of the output of the git commands that CliGitClient runs. They take text and run nothing.

private const val NUL = '\u0000'
private const val PATH_START = 3
private const val HEAD_MARKER = "*"
private const val GONE_TRACK = "[gone]"
private const val BRANCH_FIELD_COUNT = 4
private const val BRANCH_TRACK_FIELD = 3
private const val COMMIT_FIELD_COUNT = 6
private const val COMMIT_TIME_FIELD = 3
private const val COMMIT_REFS_FIELD = 4
private const val COMMIT_SUBJECT_FIELD = 5
private const val RENAME_PARTS = 3
private const val TRACK_COUNT_GROUP = 1
private val AHEAD_TRACK = Regex("""ahead (\d+)""")
private val BEHIND_TRACK = Regex("""behind (\d+)""")

/** The format of `git for-each-ref` that [parseBranch] reads. */
internal const val BRANCH_FORMAT = "%(refname:short)%00%(HEAD)%00%(upstream:short)%00%(upstream:track)"

/** The format of `git log` that [parseCommit] reads, one record per commit ended by [COMMIT_RECORD_SEPARATOR]. */
internal const val COMMIT_FORMAT = "%H%x00%P%x00%an%x00%at%x00%D%x00%s%x1e"

internal const val COMMIT_RECORD_SEPARATOR = '\u001e'

/** One change of `git status --porcelain -z`: its two status letters, its path, and the old path of a rename. */
internal class StatusEntry(val code: String, val path: String, val oldPath: String?)

/** One `for-each-ref` line in [BRANCH_FORMAT]: name, HEAD marker, upstream and its track, separated by NULs. */
internal fun parseBranch(line: String): GitBranchInfo? {
    val fields = line.split(NUL)
    val name = fields[0].trim()
    val parsed = fields.size >= BRANCH_FIELD_COUNT && name.isNotEmpty()
    if (!parsed) return null
    val track = fields[BRANCH_TRACK_FIELD].trim()
    return GitBranchInfo(
        name = name,
        current = fields[1].trim() == HEAD_MARKER,
        upstream = fields[2].trim().ifEmpty { null },
        ahead = trackCount(track, AHEAD_TRACK),
        behind = trackCount(track, BEHIND_TRACK),
        upstreamGone = track == GONE_TRACK,
    )
}

private fun trackCount(track: String, pattern: Regex): Int {
    val match = pattern.find(track)
    val count = match?.groupValues?.get(TRACK_COUNT_GROUP)
    return count?.toIntOrNull() ?: 0
}

/** One record of `git log` in [COMMIT_FORMAT]; its fields are separated by NUL, subjects can hold anything else. */
internal fun parseCommit(record: String): GitCommit? {
    val fields = record.trimStart('\n').split(NUL)
    if (fields.size < COMMIT_FIELD_COUNT || fields[0].isBlank()) return null
    val refs =
        fields[COMMIT_REFS_FIELD]
            .split(", ")
            .map { it.removePrefix("HEAD -> ") }
            .filter { it.isNotBlank() && it != "HEAD" }
    return GitCommit(
        hash = fields[0],
        parents = fields[1].split(' ').filter(String::isNotBlank),
        author = fields[2],
        time = fields[COMMIT_TIME_FIELD].toLongOrNull() ?: 0,
        subject = fields.drop(COMMIT_SUBJECT_FIELD).joinToString(NUL.toString()),
        refs = refs,
    )
}

/** Every commit of a `git log` output in [COMMIT_FORMAT]. */
internal fun parseCommits(output: String): List<GitCommit> =
    output.split(COMMIT_RECORD_SEPARATOR).mapNotNull(::parseCommit)

/** `-z --name-status`: a status, then its path; a rename or copy (R100, C75) has the old path, then the new one. */
internal fun parseNameStatus(raw: String): List<GitCommitFile> {
    val parts = raw.split(NUL).filter { it.isNotEmpty() }
    val files = ArrayList<GitCommitFile>()
    var index = 0
    while (index < parts.size) {
        val code = parts[index]
        val renamed = code.startsWith("R") || code.startsWith("C")
        if (renamed && index + 2 <= parts.lastIndex) {
            files += GitCommitFile(parts[index + 2], GitFileStatus.Renamed, oldPath = parts[index + 1])
            index += RENAME_PARTS
        } else if (index + 1 <= parts.lastIndex) {
            files += GitCommitFile(parts[index + 1], commitFileStatus(code))
            index += 2
        } else {
            break
        }
    }
    return files
}

private fun commitFileStatus(code: String): GitFileStatus =
    when (code.first()) {
        'A' -> GitFileStatus.Added
        'D' -> GitFileStatus.Deleted
        else -> GitFileStatus.Modified
    }

/**
 * The output of `git status --porcelain -z`, as lines. `-z`: paths are given as they are (no quotes nor escapes for
 * accents, spaces or line breaks), each record ends with a NUL, and a rename or copy is followed by the record of its
 * old path.
 */
internal fun parseStatusEntries(output: List<String>): List<StatusEntry> {
    val records = output.joinToString("\n").split(NUL).filter { it.length > PATH_START }
    val entries = ArrayList<StatusEntry>()
    var index = 0
    while (index < records.size) {
        val record = records[index]
        val code = record.take(2)
        val moved = 'R' in code || 'C' in code
        val old = if (moved) records.getOrNull(index + 1) else null
        entries += StatusEntry(code, record.substring(PATH_START), old)
        index += if (moved) 2 else 1
    }
    return entries
}

/** The two letters of an unmerged path: a `U` on either side, or both sides added (`AA`) or deleted (`DD`). */
internal fun isUnmerged(code: String): Boolean = 'U' in code || code == "AA" || code == "DD"

/** The status the IDE shows for [entry], or null for a change it does not show. */
internal fun statusOf(entry: StatusEntry): Pair<String, GitFileStatus>? {
    val code = entry.code
    val status =
        when {
            code == "??" -> GitFileStatus.Untracked
            isUnmerged(code) -> GitFileStatus.Conflicted
            'R' in code -> GitFileStatus.Renamed
            'A' in code -> GitFileStatus.Added
            'D' in code -> GitFileStatus.Deleted
            'M' in code -> GitFileStatus.Modified
            else -> null
        }
    return status?.let { entry.path to it }
}
