package org.example.kotlineditor.model

/** The projects opened recently, most recent first, kept by the server. */
interface RecentProjectsStore {
    /** The stored paths. */
    suspend fun load(): List<String>

    /** Replaces the stored paths. */
    suspend fun save(paths: List<String>)
}

/** [RecentProjectsStore] kept in memory. */
class InMemoryRecentProjectsStore(initial: List<String> = emptyList()) : RecentProjectsStore {
    /** The stored paths. */
    var paths: List<String> = initial
        private set

    override suspend fun load(): List<String> = paths

    override suspend fun save(paths: List<String>) {
        this.paths = paths
    }
}

/** Places of the server's machine, for the Open Project dialog (the project directories live on the server). */
interface ServerPlaces {
    /** The home directory of the user running the server, or `null` when unknown. */
    suspend fun home(): String?

    /** Knows nothing: the Open Project dialog falls back to its defaults. */
    object None : ServerPlaces {
        override suspend fun home(): String? = null
    }
}
