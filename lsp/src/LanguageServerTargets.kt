package org.example.kotlineditor.lsp

import kotlinx.serialization.json.JsonElement
import org.example.kotlineditor.lsp.provision.CandidateStatus
import org.example.kotlineditor.lsp.provision.LanguageServerCandidate
import org.example.kotlineditor.lsp.provision.LanguageServerChoiceStore
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.lsp.supervisor.LanguageServerSpec
import org.example.kotlineditor.lsp.supervisor.ServerTarget
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.Languages
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Which language server runs for which files: the user's choice for each language (saved in the data directory),
 * the server it names, downloaded when it is pinned, and the root it runs in.
 */
internal class LanguageServerTargets(private val settings: LanguageServerSettings) {
    private val choices = LanguageServerChoiceStore(settings.dataDirectory.resolve("language-servers.properties"))
    private val downloader = ToolDownloader(settings.toolsDirectory)
    private val specs = ConcurrentHashMap<String, LanguageServerSpec>()

    /** The servers [files] of [project] need, and the languages that need a server not chosen yet. */
    fun targetsOf(project: String, files: List<String>): Pair<List<ServerTarget>, List<String>> {
        val targets = LinkedHashMap<String, ServerTarget>()
        val unchosen = LinkedHashSet<String>()
        // No runtime detection here (it runs programs): it happens when the server starts.
        val handled = files.mapNotNull { file -> Languages.languageOf(file)?.let { file to it } }
        for ((file, language) in handled.filter { settings.candidates.offered(it.second).isNotEmpty() }) {
            val offered = settings.candidates.offered(language)
            val chosenId = choices.choice(language, project)
            // A language with one server needs no choice: that server is the one.
            val chosen = chosenId?.let { id -> offered.firstOrNull { it.id == id } } ?: offered.singleOrNull()
            if (chosen == null) {
                unchosen += language
            } else {
                val spec = specs.getOrPut(chosen.id) { specOf(language, chosen.id) }
                val target = ServerTarget(spec, rootOf(language, file, project))
                targets[target.id] = target
            }
            for (companion in Languages.companionsOf(file)) {
                companionTarget(companion, file, project)?.let { targets[it.id] = it }
            }
        }
        return targets.values.toList() to unchosen.toList()
    }

    // The server of a [companion] language for [file]: only where the project uses it, and never asks for a choice.
    private fun companionTarget(companion: String, file: String, project: String): ServerTarget? {
        val candidate = settings.candidates.offered(companion).singleOrNull()
        val root = TailwindProjects.rootOf(file, project).takeIf { companion == Languages.TAILWIND }
        return if (candidate == null || root == null) {
            null
        } else {
            ServerTarget(specs.getOrPut(candidate.id) { specOf(companion, candidate.id) }, root)
        }
    }

    private fun bundleOptionsOf(candidate: LanguageServerCandidate): JsonElement? {
        val bundle = candidate.bundle
        val optionsIn = candidate.optionsIn
        return if (bundle == null || optionsIn == null) null else optionsIn(downloader.bundleDirectory(bundle))
    }

    /** The servers offered for [language], their requirements checked now, and the chosen one. Blocking. */
    fun choice(language: String, project: String): LanguageServerChoice {
        val offered = settings.candidates.candidates(language).map(::infoOf)
        val only = settings.candidates.offered(language).singleOrNull()
        val chosen = choices.choice(language, project) ?: only?.id
        return LanguageServerChoice(language, Languages.nameOf(language), offered, chosen)
    }

    /** Saves [candidate] for [language], for every project. */
    fun choose(language: String, project: String, candidate: String) {
        require(settings.candidates.offered(language).any { it.id == candidate }) {
            "Unknown language server $candidate for $language"
        }
        choices.choose(language, candidate)
        choices.clearProjectChoice(language, project)
    }

    private fun infoOf(status: CandidateStatus) =
        LanguageServerCandidateInfo(
            id = status.candidate.id,
            displayName = status.candidate.displayName,
            licence = status.candidate.licence,
            strengths = status.candidate.strengths,
            drawbacks = status.candidate.drawbacks,
            available = status.available,
            details = status.details,
        )

    // The requirements are checked again at each start: PHP may have been installed since.
    private fun specOf(language: String, id: String): LanguageServerSpec {
        val candidate = settings.candidates.offered(language).firstOrNull { it.id == id }
        val options = candidate?.initializationOptions ?: candidate?.let(::bundleOptionsOf)
        return LanguageServerSpec(id, language, options, candidate?.withoutInlayHints) {
            val status = settings.candidates.candidates(language).first { it.candidate.id == id }
            settings.candidates.commandFor(status, downloader)
        }
    }

    /**
     * PHP: the closest composer.json between the file and the project, the project otherwise. A root that is the
     * project is named as the client names it: the status bar matches servers to projects by their root.
     */
    fun rootOf(language: String, file: String, project: String): String {
        val marker = Languages.rootMarkerOf(language) ?: return project
        val top = Paths.get(project).toAbsolutePath().normalize()
        var directory = absolute(file).parent
        while (directory != null && isSearched(directory, top, marker)) {
            directory = directory.parent
        }
        val found = directory?.takeIf { it.startsWith(top) && it != top }
        return found?.toString() ?: project
    }

    // Still inside the project, and without the marker: the search goes on to the parent directory.
    private fun isSearched(directory: Path, top: Path, marker: String): Boolean {
        val insideProject = directory.startsWith(top)
        val hasMarker = Files.isRegularFile(directory.resolve(marker))
        return insideProject && !hasMarker
    }

    private fun absolute(path: String): Path {
        val absolute = Paths.get(path).toAbsolutePath()
        return absolute.normalize()
    }
}
