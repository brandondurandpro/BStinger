package org.example.kotlineditor.remote

/**
 * The SSH host a remote project lives on. Shared by the UI and the JVM transport, so the dialog and the
 * preferences do not depend on the SSH implementation.
 *
 * When [alias] is set, the host is an alias from `~/.ssh/config`: ssh resolves the real host, user, port, key and
 * ProxyJump from the config, so the dialog's explicit fields are only used for a manual connection.
 */
data class SshTarget(
    val host: String,
    val user: String? = null,
    val port: Int = 22,
    val identityFile: String? = null,
    val projectPath: String? = null,
    /** Alias of `~/.ssh/config` when the connection comes from the catalog, null for a manual host. */
    val alias: String? = null,
) {
    init {
        require(host.isNotBlank()) { "The host is empty" }
        require(isValidAddress(host) && '@' !in host) { "Invalid host" }
        require(alias == null || isValidAddress(alias)) { "Invalid SSH config alias" }
        require(user == null || isValidUser(user)) { "Invalid user" }
        require(port in 1..MAX_PORT) { "Invalid SSH port $port" }
    }

    /** `user@host` for a manual target, or just the `~/.ssh/config` alias. */
    val destination: String
        get() {
            alias?.let { return it }
            val address = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return user?.takeIf { it.isNotBlank() }?.let { "$it@$address" } ?: address
        }

    /** Whether ssh should resolve the connection details from `~/.ssh/config`. */
    val usesSshConfig: Boolean get() = alias != null

    /** Identity of this host for user tags: the alias, or host, port and user for a manual target. */
    val tagKey: String
        get() = alias ?: "$host:$port:${user.orEmpty()}"

    companion object {
        const val MAX_PORT = 65_535
    }
}

// A host or alias is passed to ssh as an argument: it must not look like an option or split the argument.
private fun isValidAddress(value: String): Boolean =
    value.isNotBlank() && !value.startsWith("-") && value.none { it.isWhitespace() }

// A user name is passed to ssh as part of `user@host`.
private fun isValidUser(user: String): Boolean = isValidAddress(user) && '@' !in user
