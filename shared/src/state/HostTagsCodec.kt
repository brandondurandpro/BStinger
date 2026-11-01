package org.example.kotlineditor.state

/** Tags the user gave a remote host (production, dev, test…), kept in the client's preferences. */
object HostTagsCodec {
    const val MAX_PER_HOST = 8
    private const val MAX_HOSTS = 200

    fun encode(tags: Map<String, List<String>>): String =
        tags.entries
            .take(MAX_HOSTS)
            .joinToString("\n") { (key, values) -> "$key\t${values.joinToString(",")}" }

    fun decode(text: String): Map<String, List<String>> {
        if (text.isBlank()) return emptyMap()
        return text
            .lineSequence()
            .mapNotNull(::decodeLine)
            .toMap()
    }

    private fun decodeLine(line: String): Pair<String, List<String>>? {
        val separator = line.indexOf('\t')
        val key = if (separator <= 0) "" else line.substring(0, separator).trim()
        val tags = if (separator > 0) parseTags(line.substring(separator + 1)) else emptyList()
        val valid = separator > 0 && key.isNotEmpty() && !key.startsWith(":") && ' ' !in key && tags.isNotEmpty()
        return if (valid) key to tags else null
    }
}

/** Comma-separated tags, trimmed, without duplicates. */
fun parseTags(text: String): List<String> =
    text
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(HostTagsCodec.MAX_PER_HOST)
