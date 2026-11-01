package org.example.kotlineditor.remote

/** One host of the client's `~/.ssh/config`, resolved by `ssh -G` (host name, user, port, key, jump host). */
data class SshConfigHost(
    val alias: String,
    val hostName: String,
    val user: String?,
    val port: Int,
    val identityFile: String?,
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
