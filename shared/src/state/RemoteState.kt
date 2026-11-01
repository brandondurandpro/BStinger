package org.example.kotlineditor.state

import org.example.kotlineditor.remote.SshTarget

/** The "Remote Development" dialog: what the user typed and what the connection is doing. */
data class RemoteDialogState(
    val host: String = "",
    val user: String = "",
    val port: String = "22",
    val identityFile: String = "",
    val projectPath: String = "",
    /** Alias selected in `~/.ssh/config`; ssh then ignores the user/port/identity fields. */
    val alias: String = "",
    /** Filter of the SSH host and remote-folder list. */
    val hostSearch: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
) {
    companion object {
        fun from(target: SshTarget?): RemoteDialogState =
            if (target == null) {
                RemoteDialogState()
            } else {
                RemoteDialogState(
                    host = target.host,
                    user = target.user.orEmpty(),
                    port = target.port.toString(),
                    identityFile = target.identityFile.orEmpty(),
                    projectPath = target.projectPath.orEmpty(),
                    alias = target.alias.orEmpty(),
                )
            }
    }
}

/** The target described by the dialog fields, or a user-facing error. */
fun RemoteDialogState.toSshTarget(): Result<SshTarget> {
    val aliasValue = alias.trim().takeIf { it.isNotEmpty() }
    val host = host.trim().ifEmpty { aliasValue.orEmpty() }
    val portText = port.trim()
    val port = if (portText.isEmpty()) 22 else portText.toIntOrNull()
    return when {
        host.isEmpty() -> {
            Result.failure(IllegalArgumentException("Enter a host"))
        }

        port == null || port !in 1..SshTarget.MAX_PORT -> {
            Result.failure(IllegalArgumentException("Enter a valid port"))
        }

        else -> {
            runCatching {
                SshTarget(
                    host = host,
                    user = user.trim().takeIf { it.isNotEmpty() },
                    port = port,
                    identityFile = identityFile.trim().takeIf { it.isNotEmpty() },
                    projectPath = projectPath.trim().takeIf { it.isNotEmpty() },
                    alias = aliasValue,
                )
            }
        }
    }
}

/** Keys of the last remote connection, remembered across launches. */
object RemotePreferenceKeys {
    const val HOST = "remote.host"
    const val USER = "remote.user"
    const val PORT = "remote.port"
    const val IDENTITY = "remote.identity"
    const val PROJECT = "remote.project"
    const val ALIAS = "remote.alias"
    const val WORKSPACES = "remote.workspaces"

    fun read(preferences: Map<String, String>): SshTarget? {
        val host = preferences[HOST]?.takeIf { it.isNotBlank() } ?: return null
        return SshTarget(
            host = host,
            user = preferences[USER]?.takeIf { it.isNotBlank() },
            port = preferences[PORT]?.toIntOrNull()?.takeIf { it in 1..SshTarget.MAX_PORT } ?: 22,
            identityFile = preferences[IDENTITY]?.takeIf { it.isNotBlank() },
            projectPath = preferences[PROJECT]?.takeIf { it.isNotBlank() },
            alias = preferences[ALIAS]?.takeIf { it.isNotBlank() },
        )
    }

    fun write(target: SshTarget): Map<String, String> =
        mapOf(
            HOST to target.host,
            USER to target.user.orEmpty(),
            PORT to target.port.toString(),
            IDENTITY to target.identityFile.orEmpty(),
            PROJECT to target.projectPath.orEmpty(),
            ALIAS to target.alias.orEmpty(),
        )
}
