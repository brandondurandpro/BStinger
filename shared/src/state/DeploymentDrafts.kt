package org.example.kotlineditor.state

import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployProfile
import org.example.kotlineditor.model.relativeToRoot

// The entry selected in the project view, relative to the project; null when none.
private fun DeploymentsState.selectedRelative(): String? =
    project.tree.selectedPath?.let { relativeToRoot(it, project.root.path) }

/** Starts a new deployment, with the entry selected in the project view as its first folder when there is one. */
fun DeploymentsState.startNew() {
    val selected = selectedRelative().orEmpty()
    draft =
        DeployDraft(
            original = null,
            name = "",
            folders = listOf(DraftFolder(selected)),
            destination = project.deployDestination,
            remote = DeployDestination.parse(project.deployDestination)?.isRemote == true,
        )
    loadSshHosts()
}

/** Edits [profile]. */
fun DeploymentsState.edit(profile: DeployProfile) {
    // A folder landing at its own path follows its source, as it did when it was typed.
    val rows = profile.folders.map { DraftFolder(it.source, it.target.takeIf { target -> target != it.source }) }
    draft =
        DeployDraft(
            original = profile.name,
            name = profile.name,
            folders = rows.ifEmpty { listOf(DraftFolder("")) },
            destination = profile.destination,
            auto = profile.auto,
            remote = DeployDestination.parse(profile.destination)?.isRemote == true,
        )
    loadSshHosts()
}

private fun DeploymentsState.changeRow(index: Int, change: (DraftFolder) -> DraftFolder) {
    val current = draft ?: return
    val rows = current.folders.mapIndexed { i, row -> if (i == index) change(row) else row }
    draft = current.copy(folders = rows, error = null)
}

/** Changes the folder of the project of the row number [index]; its destination path follows unless it was typed. */
fun DeploymentsState.setSource(index: Int, text: String) = changeRow(index) { it.copy(source = text) }

/** Changes the destination path of the row number [index]; `null` makes it follow the folder again. */
fun DeploymentsState.setTarget(index: Int, text: String?) = changeRow(index) { it.copy(target = text) }

/** Adds an empty folder to the deployment being edited. */
fun DeploymentsState.addFolder() {
    val current = draft ?: return
    draft = current.copy(folders = current.folders + DraftFolder(""), error = null)
}

/** Removes the row number [index]; the last one stays, empty. */
fun DeploymentsState.removeFolder(index: Int) {
    val current = draft ?: return
    val rows = current.folders.filterIndexed { i, _ -> i != index }
    draft = current.copy(folders = rows.ifEmpty { listOf(DraftFolder("")) }, error = null)
}

/** Adds the entry selected in the project view as a folder of the deployment being edited. */
fun DeploymentsState.addSelected() {
    val current = draft ?: return
    val selected = selectedRelative().orEmpty()
    val known = current.folders.map { it.source }
    if (selected.isEmpty() || selected in known) return
    val filled = current.folders.filterNot { it.isBlank() }
    draft = current.copy(folders = filled + DraftFolder(selected), error = null)
}

/** Saves the deployment being edited, or shows what is wrong with it. */
fun DeploymentsState.saveDraft() {
    val current = draft ?: return
    val folders = foldersOf(current.folders)
    val problem = problemOf(profiles, current) ?: problemOfFolders(current.folders)
    if (problem != null || folders == null) {
        draft = current.copy(error = problem)
        return
    }
    val saved = DeployProfile(current.name.trim(), folders, current.destination.trim(), current.auto)
    val others = profiles.filterNot { it.name == current.original }
    val replaced = profiles.map { if (it.name == current.original) saved else it }
    persist(if (current.original == null) others + saved else replaced) { draft = null }
}

private fun problemOf(profiles: List<DeployProfile>, current: DeployDraft): String? {
    val name = current.name.trim()
    val taken = profiles.any { it.name == name && it.name != current.original }
    return when {
        name.isEmpty() -> "Give the deployment a name"
        taken -> "A deployment named '$name' exists already"
        DeployDestination.parse(current.destination) == null -> "Enter a folder, or host:folder for another machine"
        else -> null
    }
}
