package org.example.kotlineditor.model

private const val MAX_EXTENSIONS = 6

// Directories nobody searches in by choice, though they are not ignored by default: offered as exclusions.
private val NOISY_DIRECTORIES = listOf("dist", "vendor", "coverage", "__pycache__", "venv", ".venv")

/**
 * What the filters of Find in Files offer for a project, computed by the server from its files: every directory
 * that holds a file (relative paths, `/` separators, at any depth), the most common file [extensions] (without
 * the dot, most frequent first), and the [excludable] directories (`dist/`) the project has.
 */
data class SearchFacets(
    /** Every directory holding a file, relative to the root, sorted without regard to case. */
    val directories: List<String> = emptyList(),
    /** The most common extensions, most frequent first. */
    val extensions: List<String> = emptyList(),
    /** Directories usually left out of a search (`dist/`) that the project has. */
    val excludable: List<String> = emptyList(),
)

/** The [SearchFacets] of [files], found under the project [root]. */
fun facetsOf(files: List<FileEntry>, root: String): SearchFacets {
    val directories = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
    val counts = HashMap<String, Int>()
    for (entry in files) {
        val relative = relativeToRoot(entry.path, root)
        var end = relative.indexOf('/')
        while (end > 0) {
            directories += relative.substring(0, end)
            end = relative.indexOf('/', end + 1)
        }
        extensionOf(entry.name)?.let { counts.merge(it, 1, Int::plus) }
    }
    val byFrequency = compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
    val common = counts.entries.sortedWith(byFrequency).take(MAX_EXTENSIONS)
    val names = directories.map { it.substringAfterLast('/') }.toSet()
    val excludable = NOISY_DIRECTORIES.filter { it in names }.map { "$it/" }
    return SearchFacets(directories.toList(), common.map { it.key }, excludable)
}

// The extension of a file name, lower-cased: none for `Makefile`, `.gitignore` or `name.`.
private fun extensionOf(name: String): String? {
    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < name.length - 1
    return if (hasExtension) name.substring(dot + 1).lowercase() else null
}
