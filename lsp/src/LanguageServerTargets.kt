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
        for ((file, language) in handled) {
            if (settings.candidates.offered(language).isEmpty()) {
                addCompanions(targets, file, project)
                continue
            }
            val offered = settings.candidates.offered(language)
            val chosenId = choices.choice(language, project)
            // A language with one server needs no choice: that server is the one.
            val chosen = chosenId?.let { id -> offered.firstOrNull { it.id == id } } ?: offered.singleOrNull()
            if (chosen == null) {
                unchosen += language
            } else {
                val extras = chosen.extras != null && VueProjects.used(project, files)
                val spec = specOf(language, chosen.id, extras)
                val target = ServerTarget(spec, rootOf(language, file, project))
                targets[target.id] = target
            }
            addCompanions(targets, file, project)
        }
        return targets.values.toList() to unchosen.toList()
    }

    private fun addCompanions(targets: MutableMap<String, ServerTarget>, file: String, project: String) {
        for (companion in Languages.companionsOf(file)) {
            companionTarget(companion, file, project)?.let { targets[it.id] = it }
        }
    }

    // The server of a [companion] language for [file]: only where the project uses it, and never asks for a choice.
    private fun companionTarget(companion: String, file: String, project: String): ServerTarget? {
        val candidate = settings.candidates.offered(companion).singleOrNull()
        val root = if (companion == Languages.TAILWIND) TailwindProjects.rootOf(file, project) else project
        return if (candidate == null || root == null) {
            null
        } else {
            ServerTarget(specOf(companion, candidate.id, false), root)
        }
    }

    private fun bundleOptionsOf(candidate: LanguageServerCandidate, withExtras: Boolean): JsonElement? {
        val bundle = candidate.bundle
        val optionsIn = candidate.optionsIn
        val extras = candidate.extras?.takeIf { withExtras }?.let(downloader::bundleDirectory)
        return if (bundle == null || optionsIn == null) null else optionsIn(downloader.bundleDirectory(bundle), extras)
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
    // One spec per server and per kind of project: the Vue plugin of the TypeScript server is for Vue projects only.
    private fun specOf(language: String, id: String, withExtras: Boolean): LanguageServerSpec {
        val key = if (withExtras) "$id+extras" else id
        return specs.getOrPut(key) {
            val candidate = settings.candidates.offered(language).firstOrNull { it.id == id }
            val options = candidate?.initializationOptions ?: candidate?.let { bundleOptionsOf(it, withExtras) }
            LanguageServerSpec(id, language, options, candidate?.withoutInlayHints) {
                val status = settings.candidates.candidates(language).first { it.candidate.id == id }
                settings.candidates.commandFor(status, downloader, withExtras)
            }
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
