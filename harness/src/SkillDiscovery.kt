package org.example.kotlineditor.harness

import org.example.kotlineditor.model.AgentSkill
import java.io.File
import java.io.IOException

/** The file that makes a folder a skill. */
const val SKILL_FILE = "SKILL.md"

// How far below a folder of skills a skill may be: `skills/a/b/c/SKILL.md` is found, `skills/a/b/c/d/SKILL.md` not.
private const val MAX_DEPTH = 3

// Only the head of a `SKILL.md` is read: the front matter is at its start, the rest is the agent's to read.
private const val HEAD_BYTES = 8 * 1024
private const val FRONT_MATTER_FENCE = "---"
private const val BOM = '﻿'

/**
 * Finds the skills an agent of a team can be given: the folders holding a `SKILL.md`, searched at most three levels
 * deep under `.claude/skills` and `skills` of a project, and under `skills` of [dataDirectory], the data directory of
 * the IDE. Read-only: nothing is created, not even a missing `skills` folder. Symbolic links to folders are followed
 * (the search is bounded by its depth, so a loop ends), and a folder holding a `SKILL.md` is not searched further.
 */
class SkillDiscovery(private val dataDirectory: File) {
    /** The skills of the project at [root] and of the data directory, by name; the project's first on a same name. */
    fun discover(root: File): List<AgentSkill> {
        val projectFolders = listOf(File(root, ".claude/skills"), File(root, "skills"))
        val project = projectFolders.flatMap { folder -> skillsUnder(folder, project = true) }
        val own = skillsUnder(File(dataDirectory, "skills"), project = false)
        val unique = LinkedHashMap<String, AgentSkill>()
        for (skill in project + own) unique.putIfAbsent(skill.id, skill)
        val byName = compareBy<AgentSkill, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return unique.values.sortedWith(byName.thenBy { it.id })
    }

    private fun skillsUnder(base: File, project: Boolean): List<AgentSkill> {
        val found = ArrayList<AgentSkill>()
        if (base.isDirectory) collect(base, 1, project, found)
        return found
    }

    // The skills in the children of [folder], which are [depth] levels below the folder of skills.
    private fun collect(folder: File, depth: Int, project: Boolean, found: MutableList<AgentSkill>) {
        val children = folder.listFiles { child -> child.isDirectory }.orEmpty()
        for (child in children.sortedBy { it.name }) {
            val skillFile = File(child, SKILL_FILE)
            if (skillFile.isFile) {
                found += skillOf(child, skillFile, project)
            } else if (depth < MAX_DEPTH) {
                collect(child, depth + 1, project, found)
            }
        }
    }

    private fun skillOf(folder: File, skillFile: File, project: Boolean): AgentSkill {
        val fields = frontMatter(headOf(skillFile))
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: folder.name
        val description = fields["description"].orEmpty()
        return AgentSkill(folder.absoluteFile.path, name, description, project)
    }

    private fun headOf(file: File): String {
        val bytes =
            try {
                file.inputStream().use { it.readNBytes(HEAD_BYTES) }
            } catch (_: IOException) {
                return ""
            }
        return String(bytes, Charsets.UTF_8).removePrefix(BOM.toString())
    }
}

/**
 * The `key: value` lines of the YAML front matter at the start of [text] (between two `---` lines), values unquoted.
 * A value written as a block (`description: >`, `|`) is the indented lines that follow, joined by spaces. Text with no
 * front matter, or none closed, has no field.
 */
internal fun frontMatter(text: String): Map<String, String> {
    val body = frontMatterBody(text)
    val fields = LinkedHashMap<String, String>()
    var index = 0
    while (index < body.size) {
        val field = fieldOf(body[index])
        index++
        if (field != null) {
            val (key, value) = field
            if (isBlockStart(value)) {
                val continuation = continuationAfter(body, index)
                index += continuation.size
                fields[key] = continuation.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            } else {
                fields[key] = unquoted(value)
            }
        }
    }
    return fields
}

// The lines between the two `---` lines that open [text]; none when it has no front matter or it is not closed.
private fun frontMatterBody(text: String): List<String> {
    val lines = text.lines().map { it.trimEnd('\r') }
    val opens = lines.firstOrNull()?.trim() == FRONT_MATTER_FENCE
    val end = if (opens) lines.drop(1).indexOfFirst { it.trim() == FRONT_MATTER_FENCE } else -1
    return if (end >= 0) lines.subList(1, end + 1) else emptyList()
}

// The key and the value of a line `key: value` at the start of the line; null for any other line.
private fun fieldOf(line: String): Pair<String, String>? {
    val key = line.substringBefore(':', "").trim()
    val indented = line.startsWith(" ") || line.startsWith("\t")
    val isField = key.isNotEmpty() && ':' in line && !indented
    return if (isField) key to line.substringAfter(':').trim() else null
}

private fun isBlockStart(value: String): Boolean = value.startsWith(">") || value.startsWith("|")

// The lines after [from] that belong to a block: indented, or blank.
private fun continuationAfter(body: List<String>, from: Int): List<String> =
    body.drop(from).takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("\t") }

private fun unquoted(value: String): String {
    val quoted = value.length >= 2 && value.first() in "\"'" && value.last() == value.first()
    return if (quoted) value.substring(1, value.length - 1) else value
}
