package org.example.kotlineditor.model

import org.example.kotlineditor.remote.SshConfigHost

/**
 * Where a deployment goes: a folder of the server's disk ([host] is `null`), or a folder of another machine reached
 * over SSH ([host] is what `ssh` accepts: an alias of `~/.ssh/config`, or `user@host`).
 */
data class DeployDestination(
    /** The folder; a relative one is relative to the project on the server's disk, to the SSH user's home on a host. */
    val path: String,
    /** The machine, `null` for the server's disk. */
    val host: String? = null,
) {
    /** Whether the folder is on another machine. */
    val isRemote: Boolean get() = host != null

    /** The text the user typed: `host:path` for another machine, the path alone for the server's disk. */
    override fun toString(): String = if (host == null) path else "$host:$path"

    /** Parsing of what the user types. */
    companion object {
        // `C:\site` and `C:/site` are folders of a Windows disk, not the host `C`.
        private const val DRIVE_LETTER_LENGTH = 1

        // Characters of a host name or alias, and of the `user@` before it; nothing a shell or `ssh` would take
        // for an option or an expression.
        private val HOST = Regex("[A-Za-z0-9_][A-Za-z0-9._@-]*")

        /**
         * The destination named by [text], `null` when it is empty or names a host that cannot be one. A text
         * with a `:` after a host (`web@example.com:/var/www`, `staging:site`) is remote; a path (`/var/www`,
         * `./out`, `C:\site`) is local. A relative remote path is relative to the SSH user's home.
         */
        fun parse(text: String): DeployDestination? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val colon = trimmed.indexOf(':')
            val hostPart = if (colon < 0) "" else trimmed.substring(0, colon)
            val isLocal = colon < 0 || isPathStart(hostPart)
            return when {
                isLocal -> DeployDestination(trimmed)
                !HOST.matches(hostPart) -> null
                else -> remote(hostPart, trimmed.substring(colon + 1))
            }
        }

        // What precedes the first `:` when it is no host: nothing, a path, or the letter of a Windows drive.
        private fun isPathStart(beforeColon: String): Boolean =
            beforeColon.isEmpty() || '/' in beforeColon || beforeColon.length == DRIVE_LETTER_LENGTH

        private fun remote(host: String, path: String): DeployDestination? {
            val folder = path.ifEmpty { "." }
            return if ('\u0000' in folder) null else DeployDestination(folder, host)
        }
    }
}

/** What a deployment copied. */
data class DeployResult(
    /** Number of files written (a link counts as one). */
    val files: Int,
    /** Their size in bytes. */
    val bytes: Long,
)

/**
 * One thing to deploy: the file or directory [source] of the project, and [target], the path under the destination
 * where it lands (`/` separators, no `..`; empty is the destination itself). A directory's content is merged into
 * [target]; a file is written at [target].
 */
data class DeployItem(val source: FileEntry, val target: String)

/** Copies files of a project to a folder, on the server's disk or on another machine. */
interface Deployer {
    /**
     * Copies [items] (files and directories under [root]) to [destination], each at its own [DeployItem.target]:
     * deploying `root/web/index.html` with the target `web/index.html` writes `destination/web/index.html`, with the
     * target `site/home.html` it writes `destination/site/home.html`. Existing files are replaced, other files of the
     * destination are left alone, `.git` directories are not copied. Fails, copying nothing, when a source is outside
     * [root] or gone, a target leaves the destination, or the destination is inside a source.
     */
    suspend fun deploy(root: FileEntry, items: List<DeployItem>, destination: DeployDestination): DeployResult

    /** The machines to deploy to over SSH: the hosts of the `~/.ssh/config` of the machine that runs `ssh`. */
    suspend fun sshHosts(): List<SshConfigHost> = emptyList()

    /** Nothing deploys. */
    object None : Deployer {
        override suspend fun deploy(
            root: FileEntry,
            items: List<DeployItem>,
            destination: DeployDestination,
        ): DeployResult = throw UnsupportedOperationException("Deployment is not available")
    }
}

/**
 * A folder of a saved deployment: the [source] (path relative to the project, with `/`; empty is the whole project)
 * and the [target], the path under the destination where it lands. They are often the same path, but a repository and
 * the place it is deployed to are not always laid out alike.
 */
data class DeployFolder(
    /** Path of the file or directory in the project. */
    val source: String,
    /** Path under the destination where it lands; empty is the destination itself. */
    val target: String,
)

/**
 * A deployment the user saved for a project: its [folders] (none: the whole project) deployed to [destination] (what
 * [DeployDestination.parse] reads).
 */
data class DeployProfile(
    /** Name shown in the list of deployments. */
    val name: String,
    /** Files and directories to deploy, and where each lands. */
    val folders: List<DeployFolder>,
    /** Where they go, as typed: a folder of the server's disk, or `host:folder`. */
    val destination: String,
    /**
     * Whether the files of [sources] are deployed as soon as they change (only the changed ones); otherwise the
     * deployment runs when the user asks.
     */
    val auto: Boolean = false,
) {
    /** The paths of the project to deploy. */
    val sources: List<String> get() = folders.map { it.source }
}

/** The deployments saved for each project, keyed by project path. Kept by the server, next to the projects. */
interface DeployProfiles {
    /** The deployments of [projectPath], in the order they were saved. */
    suspend fun load(projectPath: String): List<DeployProfile>

    /** Replaces the deployments of [projectPath] with [profiles]. */
    suspend fun save(projectPath: String, profiles: List<DeployProfile>)
}

/** [DeployProfiles] kept in memory. */
class InMemoryDeployProfiles : DeployProfiles {
    private val saved = HashMap<String, List<DeployProfile>>()

    override suspend fun load(projectPath: String): List<DeployProfile> = saved[projectPath].orEmpty()

    override suspend fun save(projectPath: String, profiles: List<DeployProfile>) {
        saved[projectPath] = profiles
    }
}
