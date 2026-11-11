package org.example.kotlineditor.state

import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployFolder

private val DRIVE = Regex("^[A-Za-z]:")

/**
 * The folder [text] names, relative to the project with `/`; blank and `.` are the whole project (an empty string).
 * `null` when it is not inside the project.
 */
fun normalizeSource(text: String): String? {
    val slashed = text.trim().replace('\\', '/')
    val folder = slashed.trim('/')
    val absolute = slashed.startsWith("/") || DRIVE.containsMatchIn(folder)
    val outside = absolute || ".." in folder.split('/')
    return if (outside) null else folder.takeIf { it != "." }.orEmpty()
}

/** The path under the destination [text] names, with the same rules as a folder of the project. */
fun normalizeTarget(text: String): String? = normalizeSource(text)

/** Whether the row has nothing in it: no folder, and no path of its own. */
fun DraftFolder.isBlank(): Boolean = source.isBlank() && target.isNullOrBlank()

/** The path under the destination where the row lands: the one typed, or the source's own path. */
fun DraftFolder.effectiveTarget(): String? = normalizeTarget(target ?: source)

/**
 * The folders the rows of the form make, `null` when a row is not valid (the second is why). No row at all is the
 * whole project (an empty list).
 */
fun foldersOf(rows: List<DraftFolder>): List<DeployFolder>? {
    val filled = rows.filterNot { it.isBlank() }
    val folders =
        filled.map { row ->
            normalizeSource(
                row.source,
            )?.let { source -> row.effectiveTarget()?.let { DeployFolder(source, it) } }
        }
    return if (folders.any { it == null }) null else folders.filterNotNull().distinct()
}

/** Why the rows cannot be saved, `null` when they can. */
fun problemOfFolders(rows: List<DraftFolder>): String? {
    val filled = rows.filterNot { it.isBlank() }
    return when {
        filled.any { normalizeSource(it.source) == null } -> "A folder must be inside the project"
        filled.any { it.effectiveTarget() == null } -> "A destination path must stay inside the destination"
        else -> null
    }
}

/**
 * Where a folder of a deployment to [destination] (as typed) lands, given its [target] under the destination: the
 * destination followed by the target, `host:/var/www/docs`; the destination itself for an empty target. `null`
 * while the destination or the target is not valid.
 */
fun landingOf(destination: String, target: String): String? {
    val parsed = DeployDestination.parse(destination)
    val folder = normalizeTarget(target)
    val base = parsed?.toString()
    return when {
        base == null || folder == null -> null
        folder.isEmpty() -> base
        else -> base.trimEnd('/', '\\') + separatorOf(base) + folder
    }
}

private fun separatorOf(base: String): Char = if ('\\' in base && '/' !in base) '\\' else '/'
