package org.example.kotlineditor.deploy.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

private const val MAX_ERROR_LENGTH = 600

// A directory with more entries than this is sent in several archives, so that no command line gets too long.
private const val NAMES_PER_ARCHIVE = 200

// One transfer per item: a directory is sent with tar piped into ssh, so that nothing has to be installed on the
// host; a file with cat, which lets it land under another name.
internal suspend fun sendOverSsh(plan: DeployPlan, host: String, folder: String, ssh: List<String>) {
    for (item in plan.items) {
        val remote = remoteJoin(folder, item.target)
        if (Files.isDirectory(item.path, LinkOption.NOFOLLOW_LINKS)) {
            sendDirectory(item.path, remote, host, ssh)
        } else {
            sendFile(item.path, remote, host, ssh)
        }
    }
}

private suspend fun sendDirectory(directory: Path, remote: String, host: String, ssh: List<String>) {
    val names = Files.list(directory).use { entries -> entries.map { it.name }.filter { it != VCS_DIRECTORY }.toList() }
    if (names.isEmpty()) {
        runPipeline(listOf(builderOf(ssh, host, "mkdir -p -- ${remotePath(remote)}")), host)
        return
    }
    // The members are the entries of the directory, not the directory: its own mode and times on the host are left.
    for (batch in names.sorted().chunked(NAMES_PER_ARCHIVE)) {
        val pack =
            listOf("tar", "-c", "-f", "-", "--exclude=$VCS_DIRECTORY", "-C", directory.toString(), "--") + batch
        val unpack = builderOf(ssh, host, extractCommand(remote))
        runPipeline(listOf(ProcessBuilder(pack), unpack), host)
    }
}

private suspend fun sendFile(file: Path, remote: String, host: String, ssh: List<String>) {
    val mode = if (Files.isExecutable(file)) "755" else "644"
    val word = remotePath(remote)
    val command =
        """f=$word && mkdir -p -- "${'$'}(dirname -- "${'$'}f")" && cat > "${'$'}f" && chmod $mode "${'$'}f" """
            .trimEnd()
    val send = builderOf(ssh, host, command).redirectInput(file.toFile())
    runPipeline(listOf(send), host)
}

private fun builderOf(ssh: List<String>, host: String, command: String): ProcessBuilder =
    ProcessBuilder(ssh + listOf("-o", "BatchMode=yes", "--", host, command))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)

private suspend fun runPipeline(builders: List<ProcessBuilder>, host: String) {
    val processes =
        try {
            ProcessBuilder.startPipeline(builders)
        } catch (failure: IOException) {
            throw IOException("Cannot run tar and ssh: ${failure.message}", failure)
        }
    try {
        val failure = finishPipeline(processes, host)
        if (failure != null) throw IOException(failure)
    } finally {
        processes.forEach { if (it.isAlive) it.destroyForcibly() }
    }
}

// The error to show when the pipeline failed, null when it succeeded.
private suspend fun finishPipeline(processes: List<Process>, host: String): String? =
    coroutineScope {
        val errors = processes.map { async(Dispatchers.IO) { it.errorStream.bufferedReader().readText() } }
        val codes = processes.map { runInterruptible(Dispatchers.IO) { it.waitFor() } }
        if (codes.all { it == 0 }) {
            null
        } else {
            val texts = errors.awaitAll().map { it.trim() }
            val message = texts.joinToString("\n").trim().take(MAX_ERROR_LENGTH)
            "Deployment to $host failed (${codes.joinToString("/")}): $message"
        }
    }

// The folder of the host with [target] below it.
private fun remoteJoin(folder: String, target: String): String =
    if (target.isEmpty()) folder else folder.trimEnd('/') + "/" + target

// The shell command of the host: the folder is created, then the archive on the standard input extracted into it.
internal fun extractCommand(folder: String): String {
    val target = remotePath(folder)
    return "mkdir -p -- $target && tar -x -f - -C $target"
}

// `~` stays the home of the SSH user, which quotes would turn into a file name.
private fun remotePath(folder: String): String =
    when {
        folder == "~" -> "\"\$HOME\""
        folder.startsWith("~/") -> "\"\$HOME\"/" + shellQuote(folder.removePrefix("~/"))
        else -> shellQuote(folder)
    }

private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
