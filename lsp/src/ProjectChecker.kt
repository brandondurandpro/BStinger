package org.example.kotlineditor.lsp

import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.RuntimeKind
import org.example.kotlineditor.lsp.provision.RuntimeStatus
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.lsp.provision.WebServers
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileDiagnostics
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.model.TextRange
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Checks the whole project the way `npm run type-check` does, with `vue-tsc` (which checks `.ts` and `.vue` files):
 * the language servers only know the files that are open. The checker is pinned and downloaded like the servers, and
 * runs in the project with its `tsconfig.json`; nothing is written (`--noEmit`).
 */
internal class ProjectChecker(
    private val downloader: ToolDownloader,
    private val detector: RuntimeDetector = RuntimeDetector(),
    private val timeoutMs: Long = CHECK_TIMEOUT_MS,
) {
    /** Runs the check of [project]; blocking. [ProjectCheckResult.status] is NotReady when it could not run. */
    fun check(project: String): ProjectCheckResult {
        val directory = Paths.get(project)
        val failure = unrunnable(directory)
        if (failure != null) return failure
        return try {
            run(directory)
        } catch (failed: IOException) {
            notRun("$TOOL could not run: ${failed.message}")
        }
    }

    private fun unrunnable(directory: Path): ProjectCheckResult? {
        val node = WebServers.typescriptLanguageServer.requirements.firstOrNull()
        val found = node?.let { detector.detect(it.runtime, it.minimumVersion, it.extensions) }
        return when {
            !Files.isRegularFile(directory.resolve("tsconfig.json")) -> notRun("No tsconfig.json in the project")
            found !is RuntimeStatus.Found -> notRun("Node.js $found")
            else -> null
        }
    }

    private fun run(directory: Path): ProjectCheckResult {
        val node = checkNotNull(detector.detect(RuntimeKind.Node, "20", emptyList()) as? RuntimeStatus.Found)
        val installed = downloader.obtainBundle(WebServers.vueTsc)
        val command = WebServers.vueTscCommand(mapOf(RuntimeKind.Node to node.path), installed)
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return notRun("$TOOL took more than ${timeoutMs / MILLIS} s")
        }
        return TscOutput.parse(output.get(), directory)
    }

    private fun notRun(reason: String) = ProjectCheckResult(FeatureStatus.NotReady, TOOL, reason, emptyList())

    private companion object {
        const val TOOL = "vue-tsc"
        const val CHECK_TIMEOUT_MS = 300_000L
        const val MILLIS = 1_000L
    }
}

/** The output of `tsc --pretty false`: `path(line,column): error TS2322: message`, then indented continuations. */
internal object TscOutput {
    private const val TOOL = "vue-tsc"
    private val line = Regex("""^(.+?)\((\d+),(\d+)\): (error|warning|message) (TS\d+): (.*)$""")

    /** The problems of [output] by file, paths made absolute against [directory]. */
    fun parse(output: String, directory: Path): ProjectCheckResult {
        val byFile = LinkedHashMap<String, MutableList<Diagnostic>>()
        var last: Pair<String, Diagnostic>? = null
        for (text in output.lines()) {
            val match = line.matchEntire(text)
            if (match != null) {
                val groups = match.groupValues
                val path = directory.resolve(groups[1]).normalize().toString()
                val diagnostic = diagnosticOf(groups[2].toInt(), groups[3].toInt(), groups[4], groups[5], groups[6])
                byFile.getOrPut(path) { mutableListOf() } += diagnostic
                last = path to diagnostic
            } else if (text.startsWith(" ") && last != null) {
                val (path, previous) = last
                val list = byFile.getValue(path)
                val joined = previous.copy(message = previous.message + "\n" + text.trim())
                list[list.lastIndex] = joined
                last = path to joined
            }
        }
        val files = byFile.map { (path, list) -> FileDiagnostics(path, list) }
        val count = files.sumOf { it.diagnostics.size }
        val summary = if (count == 0) "No problem found" else "$count problems in ${files.size} files"
        return ProjectCheckResult(FeatureStatus.Ready, TOOL, summary, files)
    }

    private fun diagnosticOf(row: Int, column: Int, severity: String, code: String, message: String): Diagnostic {
        val kind =
            when (severity) {
                "error" -> DiagnosticSeverity.Error
                "warning" -> DiagnosticSeverity.Warning
                else -> DiagnosticSeverity.Information
            }
        val start = TextRange(row - 1, column - 1, row - 1, column)
        return Diagnostic(start, kind, message, source = TOOL, code = code)
    }
}
