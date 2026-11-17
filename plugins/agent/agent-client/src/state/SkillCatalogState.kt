package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.CatalogSkill
import java.io.IOException

/** The key of [skill] in the maps of what is being installed. */
fun keyOf(skill: CatalogSkill): String = "${skill.source}:${skill.path}"

/**
 * The skill catalog the server offers: the skills of the popular repositories of skills, which the user installs in the
 * data directory of the server, where agents of a team find them. The server downloads the repositories; nothing here
 * reaches the internet.
 */
@Stable
class SkillCatalogState internal constructor() {
    /** The skills listed, by repository; empty until the server answered. */
    val skills = mutableStateListOf<CatalogSkill>()

    /** True from the request to the answer of the server (a first listing downloads the repositories). */
    var loading: Boolean by mutableStateOf(false)
        private set

    /** True once the server answered once: the skills are those it listed, though maybe none. */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** Why the server could not answer at all; null otherwise. */
    var failure: String? by mutableStateOf(null)
        private set

    /** The reason each repository that could not be read gives, by repository. */
    val sourceFailures = mutableStateMapOf<String, String>()

    /** The skills being installed, by [keyOf]. */
    val installing = mutableStateMapOf<String, Boolean>()

    /** Why a skill could not be installed, by [keyOf]; forgotten at the next try. */
    val installFailures = mutableStateMapOf<String, String>()

    /**
     * Asks the server for the catalog, downloading again the repositories with [refresh]. One request at a time: asked
     * while one runs, it returns at once. A server that cannot answer leaves the skills as they were, with [failure].
     */
    suspend fun load(choices: AgentChoices, refresh: Boolean) {
        if (loading) return
        loading = true
        failure = null
        try {
            val listing = choices.skillCatalog(refresh)
            skills.clear()
            skills.addAll(listing.skills)
            sourceFailures.clear()
            sourceFailures.putAll(listing.failures)
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            failure = failed.message ?: "unknown error"
        } finally {
            loading = false
        }
    }

    /**
     * Has the server install [skill]: it shows installed once it is. A second request for a skill being installed is
     * ignored. Returns whether it was installed; the reason it was not is in [installFailures].
     */
    suspend fun install(choices: AgentChoices, skill: CatalogSkill): Boolean {
        val key = keyOf(skill)
        if (installing.put(key, true) == true) return false
        installFailures.remove(key)
        try {
            choices.installSkill(skill.source, skill.path)
            val index = skills.indexOfFirst { keyOf(it) == key }
            if (index >= 0) skills[index] = skills[index].copy(installed = true)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            installFailures[key] = failed.message ?: "unknown error"
            return false
        } finally {
            installing.remove(key)
        }
    }
}
