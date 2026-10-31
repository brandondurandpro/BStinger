package org.example.kotlineditor.remote

/**
 * The SSH host a remote project lives on. Shared by the UI and the JVM transport, so the dialog and the
 * preferences do not depend on the SSH implementation.
 */
data class SshTarget(
    val host: String,
    val user: String? = null,
    val port: Int = 22,
    val identityFile: String? = null,
    val projectPath: String? = null,
) {
    init {
        require(host.isNotBlank()) { "The host is empty" }
        require(!host.startsWith("-") && host.none { it.isWhitespace() } && '@' !in host) { "Invalid host" }
        require(user == null || isValidUser(user)) { "Invalid user" }
        require(port in 1..MAX_PORT) { "Invalid SSH port $port" }
    }

    /** `user@host`, as ssh and scp expect it. */
    val destination: String
        get() {
            val address = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return user?.takeIf { it.isNotBlank() }?.let { "$it@$address" } ?: address
        }

    companion object {
        const val MAX_PORT = 65_535
    }
}

// A user name is passed to ssh as part of `user@host`: it must not look like an option or split the argument.
private fun isValidUser(user: String): Boolean =
    user.isNotBlank() && !user.startsWith("-") && user.none { it.isWhitespace() }
