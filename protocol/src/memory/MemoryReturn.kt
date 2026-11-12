package org.example.kotlineditor.memory

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.Closeable
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.management.ManagementFactory
import kotlin.concurrent.thread

/**
 * Gives back to the system the memory a JVM of the IDE (the UI, the server) no longer uses. Without it the
 * resident memory only grows, to the highest peak of each kind:
 *
 * - **the Java heap**: a burst of garbage (a large diff read, encoded and sent) makes G1 commit as much heap,
 *   which it only gives back at the end of a concurrent cycle, and a JVM that allocates little starts none.
 *   [start] asks for one when no collection ran for [idleGcIntervalMs] (`G1PeriodicGCInterval`, which can be set
 *   while running). The same cycle frees the native memory of garbage: a paragraph of Skia is a few bytes of
 *   Java and kilobytes of native memory, freed when collected, and scrolling a diff makes megabytes of them a
 *   second without filling a heap whose limit is a quarter of the machine's memory.
 * - **the free heap**: after a collection G1 keeps up to 70 % of the heap free, 150 MB committed for 45 MB of
 *   objects in the UI. [start] lowers it to [maxHeapFreePercent] (`MaxHeapFreeRatio`, which can be set while
 *   running): 120 MB less resident memory measured, for collections that stay milliseconds on such a heap.
 * - **the native memory**: what tree-sitter, Skia or a codec free goes back to the allocator of the C library,
 *   which keeps it, in each of its per-thread arenas (4 GB measured in the UI after diffs of large files). A
 *   thread calls `malloc_trim` every [trimIntervalMs], as `-XX:TrimNativeHeapInterval` does, which neither a
 *   development run nor an older Java could be given.
 *
 * All are no-ops where they do not apply (another collector, a C library without `malloc_trim`:
 * Windows, macOS, musl): the JVM then behaves as before.
 */
class MemoryReturn(
    private val trimIntervalMs: Long = TRIM_INTERVAL_MS,
    private val idleGcIntervalMs: Long = IDLE_GC_INTERVAL_MS,
    private val maxHeapFreePercent: Long = MAX_HEAP_FREE_PERCENT,
) : Closeable {
    private val mallocTrim: MethodHandle? = find("malloc_trim", TRIM_SIGNATURE)

    @Volatile private var watcher: Thread? = null

    // The options of HotSpot that can be read and set while running; `null` in another JVM.
    private val diagnostics: HotSpotDiagnosticMXBean? =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)

    /** Whether this C library can give freed memory back (glibc). */
    val canTrim: Boolean get() = mallocTrim != null

    /** Starts giving memory back, until [close]. */
    fun start(): MemoryReturn {
        collectWhenIdle()
        keepLittleFreeHeap()
        if (canTrim && watcher == null) {
            watcher = thread(name = "memory-return", isDaemon = true) { watch() }
        }
        return this
    }

    /** Gives the freed native memory back now; whether some was. */
    fun trimNow(): Boolean {
        val trim = mallocTrim ?: return false
        val released = trim.invokeWithArguments(0L) as Int
        return released != 0
    }

    /** The interval of the collections of an idle JVM, in milliseconds; `null` when this JVM has none. */
    fun idleGcInterval(): Long? = vmOption(PERIODIC_GC)?.toLongOrNull()

    /** The share of the heap a collection may leave free, in percent; `null` when this JVM has no such limit. */
    fun maxHeapFree(): Long? = vmOption(MAX_HEAP_FREE)?.toLongOrNull()

    override fun close() {
        watcher?.interrupt()
        watcher = null
    }

    private fun watch() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(trimIntervalMs)
                trimNow()
            }
        } catch (_: InterruptedException) {
            // Closed.
        }
    }

    // Leaves alone an interval given on the command line.
    private fun collectWhenIdle() {
        if (idleGcInterval() != 0L) return
        setVmOption(PERIODIC_GC, idleGcIntervalMs)
    }

    // Leaves alone ratios given on the command line. The minimum first: it may not exceed the maximum.
    private fun keepLittleFreeHeap() {
        val isDefault = maxHeapFree() == DEFAULT_MAX_HEAP_FREE_PERCENT
        if (!isDefault) return
        val minHeapFreePercent = minOf(MIN_HEAP_FREE_PERCENT, maxHeapFreePercent)
        setVmOption(MIN_HEAP_FREE, minHeapFreePercent)
        setVmOption(MAX_HEAP_FREE, maxHeapFreePercent)
    }

    private fun setVmOption(name: String, value: Long) {
        try {
            diagnostics?.setVMOption(name, value.toString())
        } catch (_: IllegalArgumentException) {
            // Not a flag this JVM lets change: it keeps its heap as before.
        }
    }

    private fun vmOption(name: String): String? =
        try {
            diagnostics?.getVMOption(name)?.value
        } catch (_: IllegalArgumentException) {
            null // Another collector than G1, another JVM than HotSpot.
        }

    // A function of glibc, `null` in another C library.
    private fun find(name: String, signature: FunctionDescriptor): MethodHandle? {
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find(name).orElse(null) ?: return null
        return linker.downcallHandle(symbol, signature)
    }

    private companion object {
        const val PERIODIC_GC = "G1PeriodicGCInterval"
        const val MIN_HEAP_FREE = "MinHeapFreeRatio"
        const val MAX_HEAP_FREE = "MaxHeapFreeRatio"

        // What HotSpot keeps free when nothing was asked.
        const val DEFAULT_MAX_HEAP_FREE_PERCENT = 70L

        // Enough room for the garbage of a few seconds of typing or scrolling between two collections, and the heap
        // grows again as soon as more is needed.
        const val MIN_HEAP_FREE_PERCENT = 10L
        const val MAX_HEAP_FREE_PERCENT = 30L

        // `int malloc_trim(size_t pad)`.
        val TRIM_SIGNATURE: FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)

        // Often enough that a peak does not show for long, rarely enough to cost nothing: a trim with nothing to
        // give back takes microseconds, one that gives gigabytes back a few dozen milliseconds, off every other thread.
        const val TRIM_INTERVAL_MS = 30_000L

        // A concurrent cycle on a live heap of a few dozen megabytes: milliseconds, off the application threads,
        // and only when nothing else collected meanwhile. Short enough to bound what a scroll leaves behind.
        const val IDLE_GC_INTERVAL_MS = 15_000L
    }
}
