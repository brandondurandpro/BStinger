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
) : Closeable {
    private val mallocTrim: MethodHandle? = find("malloc_trim", TRIM_SIGNATURE)

    @Volatile private var watcher: Thread? = null

    // Compiled by GraalVM: its serial collector only runs when an allocation finds the young generation full.
    private val isNativeImage = System.getProperty(NATIVE_IMAGE_PROPERTY) != null

    /** Whether this C library can give freed memory back (glibc). */
    val canTrim: Boolean get() = mallocTrim != null

    /** Starts giving memory back, until [close]. */
    fun start(): MemoryReturn {
        collectWhenIdle()
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

    override fun close() {
        watcher?.interrupt()
        watcher = null
    }

    private fun watch() {
        var usedAfterCollection = usedHeap()
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(trimIntervalMs)
                val grown = usedHeap() - usedAfterCollection >= NATIVE_GARBAGE_BYTES
                if (isNativeImage && grown) {
                    collectNativeImageHeap()
                    usedAfterCollection = usedHeap()
                }
                trimNow()
            }
        } catch (_: InterruptedException) {
            // Closed.
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    // A native binary has no collector that works while the server is idle, and no periodic collection: what a
    // burst left in its heap stayed resident for good. A full collection of a few dozen megabytes alive takes
    // milliseconds, and is only asked for after the heap has grown.
    @Suppress("ExplicitGarbageCollectionCall")
    private fun collectNativeImageHeap() = System.gc()

    // Leaves alone an interval given on the command line.
    private fun collectWhenIdle() {
        if (idleGcInterval() != 0L) return
        try {
            diagnostics()?.setVMOption(PERIODIC_GC, idleGcIntervalMs.toString())
        } catch (_: IllegalArgumentException) {
            // Not a flag this JVM lets change: it keeps its heap as before.
        }
    }

    private fun vmOption(name: String): String? =
        try {
            diagnostics()?.getVMOption(name)?.value
        } catch (_: IllegalArgumentException) {
            null // Another collector than G1, another JVM than HotSpot.
        }

    private fun diagnostics(): HotSpotDiagnosticMXBean? =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)

    // A function of glibc, `null` in another C library.
    private fun find(name: String, signature: FunctionDescriptor): MethodHandle? {
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find(name).orElse(null) ?: return null
        return linker.downcallHandle(symbol, signature)
    }

    private companion object {
        const val PERIODIC_GC = "G1PeriodicGCInterval"
        const val NATIVE_IMAGE_PROPERTY = "org.graalvm.nativeimage.imagecode"

        // Less than this since the last collection is not worth one.
        const val NATIVE_GARBAGE_BYTES = 8L * 1024 * 1024

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
