package org.example.kotlineditor.lsp.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.time.Instant

/**
 * Samples the private memory of process trees — a language server and every process it spawned — into one
 * [MemoryHistory] per tree, cheaply enough to watch several servers at once (see #26, "Measuring memory").
 *
 * One coroutine per tree, never one per process: it wakes every [intervalMs] and sums what the reader returns
 * for each process of the tree. The tree itself — the root plus its [ProcessHandle.descendants] — is walked
 * again only every [treeRefreshMs], because that walk reads every process of the system. A process that
 * vanished between a walk and a read is skipped silently; when the root itself reads as gone the tree is
 * dropped: [isTracked] turns false and its history simply stops growing. A pid that another process reused
 * reads as gone too, caught by the start time recorded when the process was discovered.
 *
 * Nothing throws out of the sampling loops: a reader failure on one tree skips that tree's sample for one
 * tick and stops neither that tree's loop nor any other tree's. [close] stops every loop; the [reader] is
 * left to the one who created it.
 */
class ProcessMemorySampler(
    // How the memory of a process is read; shared by every tree.
    private val reader: ProcessMemoryReader,
    // Wall-clock milliseconds; `System::currentTimeMillis` in production, a controllable clock in tests.
    private val clock: () -> Long,
    // The sampling loops run as children of this scope; [close] cancels them, never the scope itself.
    private val scope: CoroutineScope,
    // Milliseconds between two samples of the same tree.
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    // Milliseconds between two walks of a tree's descendants.
    private val treeRefreshMs: Long = DEFAULT_TREE_REFRESH_MS,
) : Closeable {
    private val lock = Any()
    private val trees = HashMap<String, TrackedTree>()

    // The loops are children of a SupervisorJob of the given scope: one tree's failure cancels neither its
    // siblings nor the scope, while cancelling the scope still stops them all.
    private val sampling = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))

    @Volatile
    private var closed = false

    /**
     * Starts sampling the tree rooted at [process] under [key], and returns its history. Tracking again under
     * a key that is already tracked replaces that tree: a restarted language server is a new tree, its
     * history starts over. The history fills as soon as the loop wakes; if the root is already gone, the
     * first tick drops the tree again.
     */
    fun track(key: String, process: ProcessHandle): MemoryHistory {
        check(!closed) { "The sampler is closed" }
        val tree =
            TrackedTree(key, process, process.info().startInstant().orElse(null), MemoryHistory())
        synchronized(lock) {
            trees.remove(key)?.job?.cancel()
            trees[key] = tree
            // Launched inside the lock, so the job is set before any other thread can see the tree.
            tree.job = sampling.launch { sampleTree(tree) }
        }
        return tree.history
    }

    /** Stops sampling the tree under [key]; nothing happens when there is none. Its history is not cleared. */
    fun untrack(key: String) {
        synchronized(lock) { trees.remove(key)?.job?.cancel() }
    }

    /** Whether [key] is being sampled: false once untracked, closed, or dropped because its root exited. */
    fun isTracked(key: String): Boolean = synchronized(lock) { trees.containsKey(key) }

    /**
     * One sample of the IDE server's own JVM, taken on demand by whoever pushes the status: the private
     * number is the heap used (a JVM's own metric, the one its logs and dumps speak) and the resident number
     * the RSS of the whole process. That is what tells at a glance whether the language server or the server
     * itself is the one growing.
     */
    fun sampleSelf(): MemorySample {
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        // No identity check needed: this pid is this process as long as it lives.
        val resident = tryReadSelf()?.residentBytes ?: 0L
        return MemorySample(clock(), heapUsed, resident, 1)
    }

    /** Stops every tree. The [reader] is not closed: the one who created it closes it. */
    override fun close() {
        synchronized(lock) {
            closed = true
            trees.clear()
        }
        sampling.cancel()
    }

    // One tree's sampling loop, until its root exits, it is untracked, or the sampler closes.
    private suspend fun sampleTree(tree: TrackedTree) {
        try {
            while (true) {
                if (sampleOnce(tree)) return
                delay(intervalMs)
            }
        } finally {
            endTree(tree)
        }
    }

    // One tick of [tree]: refreshes its processes when due, then sums their memory into one sample. True when
    // the root is gone — the loop then ends. A reader failure skips the sample: a wrong sum would be worse
    // than none, and no failure may stop this loop or the other trees'.
    private fun sampleOnce(tree: TrackedTree): Boolean =
        try {
            refreshIfDue(tree)
            var privateBytes = 0L
            var residentBytes = 0L
            var readCount = 0
            var rootSeen = false
            var rootRead = false
            val gone = ArrayList<TrackedProcess>()
            for (process in tree.processes) {
                rootSeen = rootSeen || process.pid == tree.root.pid()
                val memory = reader.read(process.pid, process.startInstant)
                if (memory == null) {
                    // Vanished between the walk and the read, or its pid was reused: skipped silently.
                    gone += process
                    continue
                }
                privateBytes += memory.privateBytes
                residentBytes += memory.residentBytes
                readCount++
                rootRead = rootRead || process.pid == tree.root.pid()
            }
            if (rootSeen && !rootRead) {
                true
            } else {
                if (gone.isNotEmpty()) {
                    tree.processes = tree.processes - gone
                    gone.forEach { reader.release(it.pid) }
                }
                tree.history.add(clock(), privateBytes, residentBytes, readCount)
                false
            }
        } catch (_: RuntimeException) {
            // The reader failed for this tree: skip this sample and try again at the next tick.
            false
        } catch (_: IOException) {
            false
        }

    // Walks the tree again when due: the root and all its descendants, each with the start time a later read
    // checks its pid against. The processes that left the tree since the last walk are released, the ones
    // that stayed are kept (their recorded start times do not change).
    private fun refreshIfDue(tree: TrackedTree) {
        val now = clock()
        if (now < tree.nextRefreshAt) return
        tree.nextRefreshAt = now + treeRefreshMs
        val refreshed = ArrayList<TrackedProcess>()
        refreshed += TrackedProcess(tree.root.pid(), tree.rootStart)
        tree.root.descendants().use { stream ->
            stream.forEach { handle ->
                refreshed += TrackedProcess(handle.pid(), handle.info().startInstant().orElse(null))
            }
        }
        val previous = tree.processes
        tree.processes = refreshed
        previous.filter { it !in refreshed }.forEach { reader.release(it.pid) }
    }

    // Ends a tree: releases its processes, and forgets it unless a new tree already took its key.
    private fun endTree(tree: TrackedTree) {
        tree.processes.forEach { reader.release(it.pid) }
        synchronized(lock) {
            if (trees[tree.key] === tree) trees.remove(tree.key)
        }
    }

    // The memory of this process, or null when the reader fails or cannot read it.
    private fun tryReadSelf(): ProcessMemory? =
        try {
            reader.read(ProcessHandle.current().pid(), null)
        } catch (_: RuntimeException) {
            null
        } catch (_: IOException) {
            null
        }

    /** A process of a tracked tree, with the start time recorded when it was discovered. */
    private data class TrackedProcess(val pid: Long, val startInstant: Instant?)

    /** One tracked tree: its root handle, its history, and the processes the loop last saw. */
    private class TrackedTree(
        val key: String,
        val root: ProcessHandle,
        /** The root's start time, recorded when tracked, to catch a reuse of its pid. */
        val rootStart: Instant?,
        val history: MemoryHistory,
    ) {
        var job: Job? = null

        /** The processes of the tree; written by the tree's loop only. */
        var processes: List<TrackedProcess> = emptyList()

        var nextRefreshAt = 0L
    }

    private companion object {
        /** One sample of every tree every 2 s: memory moves on that scale, and the reads stay cheap. */
        const val DEFAULT_INTERVAL_MS = 2_000L

        /** The descendants are walked again every 10 s only: the walk reads every process of the system. */
        const val DEFAULT_TREE_REFRESH_MS = 10_000L
    }
}
