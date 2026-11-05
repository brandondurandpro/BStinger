package org.example.kotlineditor.server

import java.io.File

private const val BRANCHES = "refs/heads/"
private const val SYMBOLIC_PREFIX = "ref: "
private const val MAX_SYMBOLIC_DEPTH = 5
private const val DELETE_CHARACTER = '\u007f'
private const val PACKED_REFS = "packed-refs"

// SHA-1 or SHA-256 object names.
private val OBJECT_ID = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

// Characters no ref name may hold (git-check-ref-format, rules 4, 5 and 10), besides the control characters.
private const val FORBIDDEN_CHARACTERS = " ~^:?*[\\"

/**
 * Refuses [name] unless Git accepts it as a branch name, the rules of `git check-ref-format --branch`, checked here
 * without starting a process per name: above all, a name starting with `-` would be read as an option
 * (`git checkout -f` discards the changes of the working tree).
 */
internal fun requireBranchName(name: String) {
    require(isBranchName(name)) { "Not a branch name: $name" }
}

/** Whether Git accepts [name] as a branch name (see `git help check-ref-format`). */
internal fun isBranchName(name: String): Boolean {
    val reserved = name.startsWith("-") || name == "HEAD" || name == "@"
    val malformed = ".." in name || "@{" in name || "//" in name
    val badEnds = name.startsWith("/") || name.endsWith("/") || name.endsWith(".")
    val badCharacter = name.any(::isForbidden)
    val badComponent = name.split('/').any { it.startsWith(".") || it.endsWith(".lock") }
    val refused = listOf(name.isEmpty(), reserved, malformed, badEnds, badCharacter, badComponent)
    return refused.none { it }
}

private fun isForbidden(character: Char): Boolean {
    val control = character < ' ' || character == DELETE_CHARACTER
    return control || character in FORBIDDEN_CHARACTERS
}

/**
 * The commit HEAD of [root] points to, read from the files of the repository (no process): `HEAD`, then the loose
 * ref of its branch or `packed-refs`, in the common directory of a worktree. `null` when the files cannot tell (a
 * branch without commits, the reftable format, a ref outside `refs/heads`): ask Git then.
 */
internal fun headFromFiles(root: String): String? {
    val gitDirectory = gitDirectory(root)
    val common = commonDirectory(gitDirectory)
    var value = firstLine(File(gitDirectory, "HEAD"))
    // A symbolic ref may point to another one: a few steps at most (Git stops a loop alike).
    repeat(MAX_SYMBOLIC_DEPTH) {
        val symbolic = value?.takeUnless { it.matches(OBJECT_ID) }
        if (symbolic != null) value = symbolicTarget(symbolic)?.let { resolveBranch(common, it) }
    }
    return value?.takeIf { it.matches(OBJECT_ID) }
}

// The branch a symbolic ref (`ref: refs/heads/main`) points to; a per-worktree or exotic ref is left to Git.
private fun symbolicTarget(value: String): String? {
    val name = value.removePrefix(SYMBOLIC_PREFIX).trim()
    val symbolic = value.startsWith(SYMBOLIC_PREFIX)
    return name.takeIf { symbolic && it.startsWith(BRANCHES) }
}

// A loose ref wins over the packed one; what it holds may itself be a symbolic ref.
private fun resolveBranch(common: File, name: String): String? {
    val loose = firstLine(File(common, name))
    return loose ?: packedRef(common, name)
}

// `packed-refs`: a `# pack-refs` header, `<id> <name>` lines, each maybe followed by its peeled `^<id>` line.
private fun packedRef(common: File, name: String): String? {
    val file = File(common, PACKED_REFS)
    if (!file.isFile) return null
    val suffix = " $name"
    val line = runCatching { file.useLines { lines -> lines.firstOrNull { it.endsWith(suffix) } } }.getOrNull()
    return line?.substringBefore(' ')
}

// A worktree's Git directory names the shared one in its `commondir` file (relative to itself, most often).
private fun commonDirectory(gitDirectory: File): File {
    val pointer = firstLine(File(gitDirectory, "commondir")) ?: return gitDirectory
    val path = File(pointer)
    return if (path.isAbsolute) path else File(gitDirectory, pointer)
}

private fun firstLine(file: File): String? {
    if (!file.isFile) return null
    val line = runCatching { file.useLines { it.firstOrNull() } }.getOrNull()
    return line?.trim()?.ifEmpty { null }
}
