package org.example.kotlineditor.lsp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.ServerMemory
import kotlin.math.abs

private const val PUBLISH_TICK_MS = 1_000L
private const val MEMORY_PUBLISH_MS = 5_000L
private const val MIN_MOVE_BYTES = 5L * 1024 * 1024
private const val MIN_MOVE_RATIO = 0.02

/**
 * The status of the language servers as versioned snapshots: the servers and their memory, and the memory of the IDE
 * server itself. A new snapshot is published when a server changes, at once, or when a memory moved enough.
 */
internal class StatusPublisher(
    private val holdMs: Long,
    private val sampler: ProcessMemorySampler,
    private val servers: () -> List<LanguageServerInfo>,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val published = MutableStateFlow(LanguageServersStatus.Empty.copy(version = 1))

    // When the last status was published, in milliseconds since the epoch.
    private var publishedAt = 0L

    /** A server changed: the next snapshot is published now rather than at the next tick. */
    fun changed() {
        changes.trySend(Unit)
    }

    /** The status once newer than [since], or the current one after [holdMs]. */
    suspend fun status(since: Long): LanguageServersStatus {
        // Older than ours, or higher (known from a previous IDE server): the current status at once.
        if (since != published.value.version) return published.value
        return withTimeoutOrNull(holdMs) { published.first { it.version > since } } ?: published.value
    }

    /** Publishes the snapshots until the coroutine is cancelled. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            withTimeoutOrNull(PUBLISH_TICK_MS) { changes.receive() }
            publishIfMoved()
        }
    }

    private fun publishIfMoved() {
        val last = published.value
        val servers = servers()
        val self = sampler.sampleSelf()
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val server = ServerMemory(heapUsed, runtime.maxMemory(), self.residentBytes)
        val serversChanged = servers.map(::withoutMemory) != last.servers.map(::withoutMemory)
        val serverMemoryMoved = servers.any { now -> moved(privateOf(last, now.id), now.memory?.privateBytes) }
        val residentMoved = moved(last.server.residentBytes, server.residentBytes)
        val heapMoved = moved(last.server.heapUsedBytes, server.heapUsedBytes)
        val ownMemoryMoved = residentMoved || heapMoved
        val memoryMoved = serverMemoryMoved || ownMemoryMoved
        val now = System.currentTimeMillis()
        if (shouldPublish(last, servers, serversChanged, memoryMoved, now)) {
            published.value = LanguageServersStatus(last.version + 1, server, servers)
            publishedAt = now
        }
    }

    // A server that starts, stops or first reports its memory is shown at once. A memory that only moved waits for
    // MEMORY_PUBLISH_MS after the last status: the IDE server's heap moves all the time when it shares the JVM of the
    // UI, which would otherwise send a status every second to an idle IDE.
    private fun shouldPublish(
        last: LanguageServersStatus,
        servers: List<LanguageServerInfo>,
        serversChanged: Boolean,
        memoryMoved: Boolean,
        now: Long,
    ): Boolean {
        val memoryAppeared = servers.any { info -> privateOf(last, info.id) == null && info.memory != null }
        val shownAtOnce = serversChanged || memoryAppeared
        val memoryDue = now - publishedAt >= MEMORY_PUBLISH_MS
        val memoryShown = memoryMoved && memoryDue
        return shownAtOnce || memoryShown
    }

    private fun privateOf(status: LanguageServersStatus, id: String): Long? {
        val server = status.servers.firstOrNull { it.id == id }
        return server?.memory?.privateBytes
    }

    private fun withoutMemory(info: LanguageServerInfo) = info.copy(memory = null, peakPrivateBytes = 0)

    // A change worth sending: more than 5 MB and more than 2 %, so that the status bar does not flicker.
    private fun moved(before: Long?, after: Long?): Boolean {
        if (before == null || after == null) return before != after
        return abs(after - before) > maxOf(MIN_MOVE_BYTES, (before * MIN_MOVE_RATIO).toLong())
    }
}
