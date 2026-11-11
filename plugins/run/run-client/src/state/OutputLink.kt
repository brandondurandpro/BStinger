package org.example.kotlineditor.state

/** A place in a file named by a line of output (a compiler error, a stack frame). */
data class OutputLink(
    /** Index of the first character of the link in the line. */
    val start: Int,
    /** Index after the last character of the link. */
    val end: Int,
    /** The path as written: absolute, relative to the project, or just a file name. */
    val path: String,
    /** The line, counted from 1 as the tools print it. */
    val line: Int,
    /** The column, counted from 1, or 1 when the output names none. */
    val column: Int,
    /** The address, for a link to a web page (`http://localhost:5173/`); then [path] is blank. */
    val url: String? = null,
)

private const val SOURCE_EXTENSIONS =
    "kt|kts|java|scala|groovy|gradle|js|jsx|ts|tsx|py|rs|go|c|h|cc|cpp|hpp|cs|php|rb|swift|sh|yaml|yml|json|xml|toml"

// `file:///home/me/p/A.kt:12:5` (Kotlin compiler), `/home/me/p/A.kt:12:5`, `src/A.kt:12`, `C:\p\A.kt:12` (a short name
// like `RUNNER~1` too), and the
// `MyTest.kt:7` of a JVM stack frame (`at p.MyTest.run(MyTest.kt:7)`), which names only the file.
private val FILE_POSITION =
    Regex("""(?:file://)?((?:[A-Za-z]:)?[\w./\\$@+~-]*[\w$-]\.(?:$SOURCE_EXTENSIONS)):(\d+)(?::(\d+))?""")

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/** [text] without the colors and cursor moves some tools print even when their output is not a terminal. */
fun stripAnsi(text: String): String = if (text.indexOf('\u001B') < 0) text else ANSI.replace(text, "")

private val WEB_ADDRESS = Regex("""https?://[^\s"'<>`]+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

// The web addresses of [text]; the punctuation that ends a sentence is not part of the address.
private fun findWebLinks(text: String): List<OutputLink> {
    val links = ArrayList<OutputLink>()
    for (match in WEB_ADDRESS.findAll(text)) {
        val address = match.value.trimEnd { it in TRAILING_PUNCTUATION }
        if (address.length <= "https://".length - 1) continue
        val start = match.range.first
        links += OutputLink(start, start + address.length, "", 1, 1, address)
    }
    return links
}

/** The places and web pages [text] names, left to right. */
fun findOutputLinks(text: String): List<OutputLink> {
    if (text.indexOf(':') < 0 && text.indexOf('(') < 0) return emptyList()
    val web = findWebLinks(text)
    val links = ArrayList<OutputLink>(web)
    for (match in FILE_POSITION.findAll(text)) {
        val overlapsWeb = web.any { match.range.first < it.end && it.start <= match.range.last }
        val line = match.groupValues[2].toIntOrNull()
        val column = match.groupValues[3].toIntOrNull() ?: 1
        if (!overlapsWeb && line != null) {
            links += OutputLink(match.range.first, match.range.last + 1, match.groupValues[1], line, column)
        }
    }
    return links.sortedBy { it.start }
}
