package org.example.kotlineditor.git.server

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Length of the short commit hashes shown by the IDE. */
internal const val SHORT_HASH_LENGTH = 8

/** How long a local Git command may run: a hanging fsmonitor, hook or helper, or a dead network mount. */
internal const val GIT_TIMEOUT_SECONDS = 60L

/**
 * Git run in [root], never asking for a password on a terminal it has not. Without optional locks, a command that
 * only reads (`git status`, `git diff`) leaves the index alone: rewriting it would wake the watch of the disk, which
 * refreshes the status again; the commands that change the repository still take the locks they need.
 */
internal fun gitProcess(root: String, args: List<String>): ProcessBuilder =
    ProcessBuilder(listOf("git") + args)
        .directory(File(root))
        .apply {
            environment()["GIT_OPTIONAL_LOCKS"] = "0"
            environment()["GIT_TERMINAL_PROMPT"] = "0"
        }

/**
 * Runs Git in [root] and returns its lines, or `null` when it fails or times out (read-only queries). Like every
 * command, it is stopped when its thread is interrupted (see [execGit]).
 */
internal fun gitOutput(root: String, vararg args: String): List<String>? {
    val run =
        try {
            execGit(root, args.toList(), GIT_TIMEOUT_SECONDS)
        } catch (_: IOException) {
            null
        }
    return run?.takeIf { it.exitCode == 0 }?.lines
}

/** Runs Git in [root] and returns its output; throws [IOException] with Git's message when it fails. */
internal fun runGit(root: String, vararg args: String): String = runGit(root, GIT_TIMEOUT_SECONDS, *args)

/** Same as [runGit], but gives up after [timeoutSeconds]: network operations can hang. */
internal fun runGit(root: String, timeoutSeconds: Long, vararg args: String): String {
    val run = execGit(root, args.toList(), timeoutSeconds)
    if (run.exitCode != 0) {
        val detail = failureDetail(run.errors, run.text) ?: "exit ${run.exitCode}"
        throw IOException("git ${args.firstOrNull()}: $detail")
    }
    return run.text
}

/**
 * Same as [runGit] for a command that writes the index or the working tree (checkout, add, commit, pull): those of
 * one working tree run one at a time, so that two requests never collide on Git's `index.lock`. Waiting for the lock
 * is cancelled like the command itself.
 */
internal fun runGitOnIndex(root: String, vararg args: String): String = runGitOnIndex(root, GIT_TIMEOUT_SECONDS, *args)

/** Same as [runGitOnIndex], with the [timeoutSeconds] of a network operation. */
internal fun runGitOnIndex(root: String, timeoutSeconds: Long, vararg args: String): String {
    val lock = indexLock(root)
    lock.lockInterruptibly()
    try {
        return runGit(root, timeoutSeconds, *args)
    } finally {
        lock.unlock()
    }
}

private val indexLocks = ConcurrentHashMap<String, ReentrantLock>()

// One lock per working tree (each has its own index), whatever path names it.
private fun indexLock(root: String): ReentrantLock {
    val directory = File(root)
    val key = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
    return indexLocks.computeIfAbsent(key) { ReentrantLock(true) }
}

// Git's reason for a failure: its first `error:` or `fatal:` line (later ones are often just "Aborting"), or else
// the last line it printed.
private fun failureDetail(errors: String, output: String): String? {
    val lines = (errors.lines() + output.lines()).map(String::trim).filter(String::isNotEmpty)
    return lines.firstOrNull { it.startsWith("error:") || it.startsWith("fatal:") } ?: lines.lastOrNull()
}
