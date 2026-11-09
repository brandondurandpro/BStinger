package org.example.kotlineditor.state

/** What a run configuration does with the output of its command. */
enum class RunKind {
    /** Runs a program: the output is shown as it comes. */
    Run,

    /** Runs tests: the output is also read into the tree of results. */
    Tests,
}

/**
 * A command the user can run in the project: a Gradle or Amper task, the `main` of an application, a script.
 * It is saved with the project's preferences.
 */
data class RunConfiguration(
    /** Stable id, so that renaming a configuration keeps it selected. */
    val id: String,
    /** Name shown in the selector. */
    val name: String,
    /** Whether the output is read as test results. */
    val kind: RunKind,
    /** The command line, as typed: the program, then its arguments; quotes group words (see [splitCommandLine]). */
    val commandLine: String,
    /** Directory the command runs in, relative to the project; blank for the project itself. */
    val workingDirectory: String = "",
)

private const val FIELD_SEPARATOR = '\t'
private const val ESCAPE = '\\'

/** Splits a command line into its words: blanks separate them, single or double quotes group blanks into one. */
fun splitCommandLine(commandLine: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var inWord = false
    for (char in commandLine) {
        val open = quote
        when {
            open != null && char == open -> {
                quote = null
            }

            open != null -> {
                word.append(char)
            }

            char == '"' || char == '\'' -> {
                quote = char
                inWord = true
            }

            char.isWhitespace() -> {
                if (inWord) words += word.toString()
                word.clear()
                inWord = false
            }

            else -> {
                word.append(char)
                inWord = true
            }
        }
    }
    if (inWord) words += word.toString()
    return words
}

/** The configurations as the text saved in the preferences: one per line, fields separated by a tab. */
fun encodeConfigurations(configurations: List<RunConfiguration>): String =
    configurations.joinToString("\n") { configuration ->
        listOf(
            configuration.id,
            configuration.kind.name,
            configuration.name,
            configuration.workingDirectory,
            configuration.commandLine,
        ).joinToString(FIELD_SEPARATOR.toString(), transform = ::escape)
    }

/** Reads what [encodeConfigurations] saved; a line that is not a configuration is skipped. */
fun decodeConfigurations(text: String?): List<RunConfiguration> {
    if (text.isNullOrBlank()) return emptyList()
    return text.lines().mapNotNull(::decodeConfiguration)
}

private fun decodeConfiguration(line: String): RunConfiguration? {
    val fields = splitEscaped(line)
    val kind = RunKind.entries.firstOrNull { it.name == fields.getOrNull(1) }
    val id = fields.getOrNull(0).orEmpty()
    if (fields.size != FIELD_COUNT || kind == null || id.isEmpty()) return null
    return RunConfiguration(
        id = id,
        name = fields[2],
        kind = kind,
        commandLine = fields[4],
        workingDirectory = fields[3],
    )
}

private const val FIELD_COUNT = 5

private fun escape(value: String): String {
    val escaped = StringBuilder()
    for (char in value) {
        when (char) {
            ESCAPE -> escaped.append("\\\\")
            '\n' -> escaped.append("\\n")
            FIELD_SEPARATOR -> escaped.append("\\t")
            else -> escaped.append(char)
        }
    }
    return escaped.toString()
}

private fun splitEscaped(line: String): List<String> {
    val fields = ArrayList<String>()
    val field = StringBuilder()
    var escaped = false
    for (char in line) {
        when {
            escaped -> {
                field.append(unescaped(char))
                escaped = false
            }

            char == ESCAPE -> {
                escaped = true
            }

            char == FIELD_SEPARATOR -> {
                fields += field.toString()
                field.clear()
            }

            else -> {
                field.append(char)
            }
        }
    }
    fields += field.toString()
    return fields
}

private fun unescaped(char: Char): Char =
    when (char) {
        'n' -> '\n'
        't' -> FIELD_SEPARATOR
        else -> char
    }

/**
 * Configurations a project suggests from the files at its root: [names] are the entries of the root. An Amper
 * project (`kotlin` or `amper` wrapper) and a Gradle one (`gradlew`) each get a test run and an application run.
 * [windows] picks the `.bat` wrappers, which are the ones a Windows server can run.
 */
fun suggestedConfigurations(names: Set<String>, windows: Boolean): List<RunConfiguration> {
    val suggestions = ArrayList<RunConfiguration>()
    val amper = listOf("kotlin", "amper").firstOrNull { it in names }
    if (amper != null) {
        val wrapper = if (windows) "$amper.bat" else "./$amper"
        suggestions += RunConfiguration("amper-test", "All tests (Amper)", RunKind.Tests, "$wrapper test")
        suggestions += RunConfiguration("amper-run", "Run (Amper)", RunKind.Run, "$wrapper run")
    }
    if ("gradlew" in names) {
        val wrapper = if (windows) "gradlew.bat" else "./gradlew"
        suggestions += RunConfiguration("gradle-test", "All tests (Gradle)", RunKind.Tests, "$wrapper test")
        suggestions += RunConfiguration("gradle-run", "Run (Gradle)", RunKind.Run, "$wrapper run")
    }
    return suggestions
}
