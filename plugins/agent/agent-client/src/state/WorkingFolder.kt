package org.example.kotlineditor.state

// The folder a conversation works in: the project's root, or a folder under it chosen before the first prompt. Paths
// are those of the server, which may write them with `/` or with a backslash (Windows): what is compared is their
// segments, and what is written keeps the separator of the project's root.

/** A path as its prefix (the drive, the root, or nothing for a relative path) and the folders it names. */
private class PathParts(val prefix: String, val segments: List<String>, val caseSensitive: Boolean)

private val DRIVE = Regex("^[A-Za-z]:")

private fun partsOf(path: String): PathParts {
    val text = path.trim()
    val drive = DRIVE.find(text)?.value.orEmpty()
    val rest = text.removePrefix(drive)
    val unc = rest.startsWith("\\\\")
    val rooted = rest.startsWith("/") || rest.startsWith("\\")
    val prefix =
        when {
            drive.isNotEmpty() -> drive.uppercase()
            unc -> "\\\\"
            rooted -> "/"
            else -> ""
        }
    val names = rest.split('/', '\\').filter { it.isNotEmpty() && it != "." }
    val resolved = if (prefix.isEmpty()) names else collapsed(names)
    return PathParts(prefix, resolved, caseSensitive = drive.isEmpty())
}

// `..` removes the folder before it; one above the top stays where it is.
private fun collapsed(names: List<String>): List<String> {
    val kept = ArrayList<String>()
    for (name in names) {
        if (name != "..") kept.add(name) else if (kept.isNotEmpty()) kept.removeLast()
    }
    return kept
}

/** Whether [path] is absolute: rooted, or with a drive. */
internal fun isAbsolute(path: String): Boolean = partsOf(path).prefix.isNotEmpty()

// The separator the server writes its paths with, read from its project's root.
internal fun separatorOf(root: String): String = if (root.contains('\\') && !root.contains('/')) "\\" else "/"

private fun writtenAs(root: String, parts: PathParts): String {
    val separator = separatorOf(root)
    val body = parts.segments.joinToString(separator)
    val rooted = parts.prefix == "/" || parts.prefix == "\\\\"
    return when {
        parts.prefix.isEmpty() -> body
        rooted -> parts.prefix + body
        else -> parts.prefix + separator + body
    }
}

/**
 * The absolute path the user typed in the field of the folder: [typed] itself when absolute, else under [root]; the
 * root when it is empty. `.` and `..` are resolved.
 */
internal fun resolveFolder(root: String, typed: String): String {
    val rootParts = partsOf(root)
    if (typed.isBlank()) return writtenAs(root, rootParts)
    val typedParts = partsOf(typed)
    if (typedParts.prefix.isNotEmpty()) return writtenAs(root, typedParts)
    val joined = collapsed(rootParts.segments + typedParts.segments)
    return writtenAs(root, PathParts(rootParts.prefix, joined, rootParts.caseSensitive))
}

/** [path] relative to [root], written with `/`; empty for the root itself, null when [path] is not under it. */
internal fun relativeFolder(root: String, path: String): String? {
    val rootParts = partsOf(root)
    val pathParts = partsOf(path)
    if (rootParts.prefix != pathParts.prefix) return null
    if (pathParts.segments.size < rootParts.segments.size) return null
    val under = rootParts.segments.indices.all { rootParts.segments[it].equals(pathParts.segments[it], ignoreCase = !rootParts.caseSensitive) }
    if (!under) return null
    return pathParts.segments.drop(rootParts.segments.size).joinToString("/")
}

/** The directory [relative] names under [root], written like [root]. */
internal fun folderIn(root: String, relative: String): String = resolveFolder(root, relative)

/** What the field of the folder shows for [relative] under [root]: its path, ending with a separator. */
internal fun fieldFor(root: String, relative: String): String = folderIn(root, relative).trimEnd('/', '\\') + separatorOf(root)

/** The folder of [path] that contains it; null for a root. */
internal fun parentFolder(path: String): String? {
    val parts = partsOf(path)
    if (parts.segments.isEmpty()) return null
    val up = PathParts(parts.prefix, parts.segments.dropLast(1), parts.caseSensitive)
    return writtenAs(path, up)
}

/** What the button of the folder says: the root of the project, or the folder under it. */
internal fun folderLabel(relative: String): String = if (relative.isEmpty()) "Project root" else relative

/** Where the agent of [conversation] works in [workspaceRoot]: the root, or its [Conversation.subdirectory]. */
internal fun Conversation.directoryIn(workspaceRoot: String): String = folderIn(workspaceRoot, subdirectory)

/** The last folder of [path], as the list of the conversations and the picker call it; the path itself for a root. */
fun folderNameOf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return name.ifEmpty { path }
}
