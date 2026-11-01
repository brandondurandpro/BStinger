package org.example.kotlineditor.state

import org.example.kotlineditor.remote.SshTarget

/** A remote folder the user already opened, kept on the client to find it again quickly. */
data class RemoteWorkspace(val target: SshTarget, val path: String, val lastOpened: Long) {
    /** Alias when the connection comes from `~/.ssh/config`, otherwise the host. */
    val label: String get() = target.alias ?: target.host

    /** What the remote dialog's search field matches. */
    val searchText: String
        get() = "${target.alias.orEmpty()} ${target.host} ${target.user.orEmpty()} ${target.port} $path"

    /** Stable identity of a saved place, including the port for manual targets. */
    val key: String
        get() {
            val place = target.alias ?: "${target.host}:${target.port}:${target.user.orEmpty()}"
            return "$place\t$path"
        }
}

/** True when this entry is the same host (alias or manual target) and folder. */
fun RemoteWorkspace.matches(other: SshTarget, otherPath: String): Boolean {
    if (path != otherPath) return false
    val mine = target.alias
    val theirs = other.alias
    return if (mine != null || theirs != null) {
        mine == theirs
    } else {
        target.host == other.host && target.user == other.user && target.port == other.port
    }
}

/** Line-based codec for the preference store: one workspace per line, tab-separated, escaped. */
object RemoteWorkspaceCodec {
    const val MAX = 50
    private const val FIELD_COUNT = 7

    fun encode(entries: List<RemoteWorkspace>): String =
        entries.take(MAX).joinToString("\n") { entry ->
            listOf(
                entry.target.alias.orEmpty(),
                entry.target.host,
                entry.target.user.orEmpty(),
                entry.target.port.toString(),
                entry.target.identityFile.orEmpty(),
                entry.path,
                entry.lastOpened.toString(),
            ).joinToString("\t", transform = ::escape)
        }

    fun decode(text: String): List<RemoteWorkspace> {
        if (text.isBlank()) return emptyList()
        return text
            .lineSequence()
            .mapNotNull(::decodeLine)
            .sortedByDescending { it.lastOpened }
            .distinctBy { it.key }
            .take(MAX)
            .toList()
    }

    private fun decodeLine(line: String): RemoteWorkspace? {
        val parts = line.split('\t')
        if (parts.size != FIELD_COUNT) return null
        return runCatching {
            val values = parts.map(::unescape)
            val port = values[3].toIntOrNull() ?: error("invalid port")
            val path = values[5].takeIf { it.isNotEmpty() } ?: error("empty path")
            val lastOpened = values[6].toLongOrNull() ?: error("invalid time")
            RemoteWorkspace(
                target =
                SshTarget(
                    host = values[1],
                    user = values[2].takeIf { it.isNotEmpty() },
                    port = port,
                    identityFile = values[4].takeIf { it.isNotEmpty() },
                    alias = values[0].takeIf { it.isNotEmpty() },
                ),
                path = path,
                lastOpened = lastOpened,
            )
        }.getOrNull()
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\' || index >= value.length) {
                result.append(char)
                continue
            }
            when (val next = value[index++]) {
                't' -> result.append('\t')
                'n' -> result.append('\n')
                else -> result.append(next)
            }
        }
        return result.toString()
    }
}
