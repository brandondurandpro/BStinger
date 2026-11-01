package org.example.kotlineditor.lsp.provision

import java.nio.file.Path

/** A runtime a language server needs, at a minimum version. */
data class Requirement(
    /** The runtime. */
    val runtime: RuntimeKind,
    /** Oldest version that works, or null for any. */
    val minimumVersion: String?,
)

/**
 * A language server the user may choose for a language, with what is shown in the choice panel: strengths,
 * drawbacks, licence and requirements (see #26, "Choosing the PHP server").
 */
data class LanguageServerCandidate(
    /** Stable identifier, saved with the user's choice (`phpactor`). */
    val id: String,
    /** Name shown to the user. */
    val displayName: String,
    /** Language identifier (`php`). */
    val language: String,
    /** Licence, in a few words. */
    val licence: String,
    /** What it does well, one short sentence each. */
    val strengths: List<String>,
    /** What it does less well, one short sentence each. */
    val drawbacks: List<String>,
    /** Runtimes it needs. */
    val requirements: List<Requirement>,
    /** The artifact downloaded on first use, null for a server already on the machine. */
    val tool: PinnedTool?,
    /** The command line, from the found runtimes and the installed artifact (null without [tool]). */
    val command: (runtimes: Map<RuntimeKind, Path>, installed: Path?) -> List<String>,
)

/** A candidate and whether it can run on this server machine. */
data class CandidateStatus(
    /** The candidate. */
    val candidate: LanguageServerCandidate,
    /** Whether every requirement is met. */
    val available: Boolean,
    /** What was detected, one sentence per requirement ("PHP 8.3.6 found", "Node.js not found: …"). */
    val details: List<String>,
    /** Paths of the runtimes that were found. */
    val runtimes: Map<RuntimeKind, Path>,
)

/** The language servers known for each language, with their requirements checked on this machine. */
class CandidateRegistry(
    private val detector: RuntimeDetector = RuntimeDetector(),
    private val known: List<LanguageServerCandidate> = PhpServers.all,
) {
    /** The candidates of [language], without checking their requirements (which runs programs). */
    fun offered(language: String): List<LanguageServerCandidate> = known.filter { it.language == language }

    /** The candidates of [language] (`php`), in the order they are offered; empty for a language with none. */
    fun candidates(language: String): List<CandidateStatus> = known.filter { it.language == language }.map(::status)

    /** The command that starts [status]'s server, installing its artifact first when needed. Blocking. */
    fun commandFor(status: CandidateStatus, downloader: ToolDownloader): List<String> {
        check(status.available) { "${status.candidate.displayName} cannot run here: ${status.details.joinToString()}" }
        return status.candidate.command(status.runtimes, status.candidate.tool?.let(downloader::obtain))
    }

    private fun status(candidate: LanguageServerCandidate): CandidateStatus {
        val results = candidate.requirements.map { detector.detect(it.runtime, it.minimumVersion) }
        return CandidateStatus(
            candidate = candidate,
            available = results.all { it is RuntimeStatus.Found },
            details = results.map(::describe),
            runtimes = results.filterIsInstance<RuntimeStatus.Found>().associate { it.kind to it.path },
        )
    }

    private fun describe(status: RuntimeStatus): String =
        when (status) {
            is RuntimeStatus.Found -> {
                "${status.kind.displayName} ${status.version} found"
            }

            is RuntimeStatus.Missing -> {
                "${status.reason}: install ${status.kind.displayName} or set its path in Preferences › " +
                    "Language servers"
            }

            is RuntimeStatus.TooOld -> {
                "${status.kind.displayName} ${status.version} found, but ${status.minimum} or newer is needed"
            }
        }
}
