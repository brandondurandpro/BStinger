package org.example.kotlineditor.server

import org.example.kotlineditor.model.GitCommit
import java.io.File
import java.io.IOException
import java.io.Reader

private const val RECORD_END = '\u001e'
private const val FIELD_END = '\u0000'
private const val SEARCH_FIELDS = 7
private const val TIME_FIELD = 3
private const val REFS_FIELD = 4
private const val SUBJECT_FIELD = 5
private const val BODY_FIELD = 6

// Hash, parents, author, date, refs, subject and body: separated by NUL, each commit ended by RS (0x1e).
private const val SEARCH_FORMAT = "--format=%H%x00%P%x00%an%x00%at%x00%D%x00%s%x00%b%x1e"

/**
 * The commits of [ref] whose message (subject or body) or author contains [query], ignoring case, or whose hash
 * starts with it: the page of [limit] matches after the first [skip] ones, newest first.
 *
 * Git cannot do it alone (`--grep` and `--author` must both match), so its log is read as it comes and filtered
 * here; reading stops, and Git is stopped, once the page is full, so a page near the top of a long history is fast.
 */
internal fun searchCommits(root: String, ref: String, query: String, skip: Int, limit: Int): List<GitCommit> {
    val needle = query.trim().lowercase()
    val process =
        ProcessBuilder("git", "log", SEARCH_FORMAT, ref, "--")
            .directory(File(root))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    val page =
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                // Lazy: taking the page stops reading the records once it is full.
                records(reader)
                    .mapNotNull { parseSearchRecord(it) }
                    .filter { it.matches(needle) }
                    .drop(skip)
                    .take(limit)
                    .map { it.commit }
                    .toList()
            }
        }.onFailure { process.destroy() }
            .getOrThrow()
    // A full page leaves the rest of the history unread: Git is stopped. Otherwise it ran to its end, and its exit
    // code tells whether the ref existed.
    if (page.size >= limit) {
        process.destroy()
    } else if (process.waitFor() != 0) {
        // An unknown ref: let Git say so, with its message.
        runGit(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        throw IOException("git log: cannot read the history of $ref")
    }
    return page
}

// The records Git prints, as they come.
private fun records(reader: Reader): Sequence<String> =
    sequence {
        val record = StringBuilder()
        var c = reader.read()
        while (c >= 0) {
            if (c.toChar() == RECORD_END) {
                yield(record.toString())
                record.setLength(0)
            } else {
                record.append(c.toChar())
            }
            c = reader.read()
        }
    }

private class SearchRecord(val commit: GitCommit, val body: String) {
    fun matches(needle: String): Boolean =
        needle.isEmpty() ||
            commit.hash.startsWith(needle) ||
            commit.author.lowercase().contains(needle) ||
            commit.subject.lowercase().contains(needle) ||
            body.lowercase().contains(needle)
}

private fun parseSearchRecord(record: String): SearchRecord? {
    val fields = record.trimStart('\n').split(FIELD_END)
    if (fields.size < SEARCH_FIELDS || fields[0].isBlank()) return null
    val refs =
        fields[REFS_FIELD]
            .split(", ")
            .map { it.removePrefix("HEAD -> ") }
            .filter { it.isNotBlank() && it != "HEAD" }
    val commit =
        GitCommit(
            hash = fields[0],
            parents = fields[1].split(' ').filter(String::isNotBlank),
            author = fields[2],
            time = fields[TIME_FIELD].toLongOrNull() ?: 0,
            subject = fields[SUBJECT_FIELD],
            refs = refs,
        )
    return SearchRecord(commit, fields.drop(BODY_FIELD).joinToString(FIELD_END.toString()))
}
