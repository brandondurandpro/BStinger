package org.example.kotlineditor.lsp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.memory.CpuMeter
import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.ServerMemory
import kotlin.math.abs

private const val PUBLISH_TICK_MS = 1_000L
private const val MEMORY_PUBLISH_MS = 5_000L
private const val MIN_MOVE_BYTES = 5L * 1024 * 1024
private const val MIN_MOVE_RATIO = 0.02

// A CPU use that moved by this many points or less is not worth a status: an idle IDE reads 0 or 1.
private const val CPU_NOISE_POINTS = 2

// The processor use is measured over a few seconds: over one second the OS's 10 to 15 ms counting steps would show.
private const val CPU_WINDOW_NANOS = 3_000_000_000L

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

    // This process, which a client compares with its own: the IDE server may run in the process of the UI.
    private val process = ProcessIdentity.current()

    // The processor use of this process, measured at the ticks, each time over the last few seconds.
    private val cpu = CpuMeter.ofCurrentProcess(CPU_WINDOW_NANOS)

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
        val server =
            ServerMemory(
                heapUsedBytes = heapUsed,
                heapMaxBytes = runtime.maxMemory(),
                residentBytes = self.residentBytes,
                pid = process.pid,
                startedAtMillis = process.startedAtMillis,
                cpuPercent = cpu.percent() ?: ServerMemory.NOT_MEASURED,
            )
        val serversChanged = servers.map(::withoutMemory) != last.servers.map(::withoutMemory)
        val serverMemoryMoved = servers.any { now -> moved(privateOf(last, now.id), now.memory?.privateBytes) }
        val residentMoved = moved(last.server.residentBytes, server.residentBytes)
        val heapMoved = moved(last.server.heapUsedBytes, server.heapUsedBytes)
        val cpuMoved = cpuMoved(last.server.cpuPercent, server.cpuPercent)
        val ownMemoryMoved = residentMoved || heapMoved || cpuMoved
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

    // A processor use that appeared, vanished or moved by more than the noise.
    private fun cpuMoved(before: Int, after: Int): Boolean {
        val measured = before != ServerMemory.NOT_MEASURED && after != ServerMemory.NOT_MEASURED
        if (!measured) return before != after
        return abs(after - before) > CPU_NOISE_POINTS
    }

    // A change worth sending: more than 5 MB and more than 2 %, so that the status bar does not flicker.
    private fun moved(before: Long?, after: Long?): Boolean {
        if (before == null || after == null) return before != after
        return abs(after - before) > maxOf(MIN_MOVE_BYTES, (before * MIN_MOVE_RATIO).toLong())
    }
}
