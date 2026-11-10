package org.example.kotlineditor.remote

/** One host of the client's `~/.ssh/config`, resolved by `ssh -G` (host name, user, port, key, jump host). */
data class SshConfigHost(
    /** The name to give `ssh`. */
    val alias: String,
    /** The machine it reaches. */
    val hostName: String,
    /** The user, when the configuration names one. */
    val user: String?,
    /** The SSH port. */
    val port: Int,
    /** The private key, when there is one. */
    val identityFile: String?,
    /** The jump host, when there is one. */
    val proxyJump: String?,
) {
    /** `user@host` (with `:port` when it is not 22), shown under the alias. */
    val summary: String
        get() =
            buildString {
                user?.takeIf { it.isNotBlank() }?.let { append(it).append('@') }
                append(hostName)
                if (port != 22) append(':').append(port)
            }

    /** What the search field matches. */
    val searchText: String get() = "$alias $hostName ${user.orEmpty()} $port ${proxyJump.orEmpty()}"
}
