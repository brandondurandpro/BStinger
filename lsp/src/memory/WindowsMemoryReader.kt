package org.example.kotlineditor.lsp.memory

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.time.Instant

/**
 * Reads process memory through kernel32, with the Foreign Function & Memory API only (no JNA, no OSHI): a
 * process handle is opened once per process and kept open, which is cheaper than reopening at every sample
 * and pins the process object, so its pid cannot be reused under the handle. `PrivateUsage` (the private
 * commit charge) is the private metric, `WorkingSetSize` the resident one.
 *
 * An exited process still has an object while a handle is open, and its last counters can still be read, so
 * the exit code is checked first: a process that exited reads as gone like on the other OSes. Everything is
 * serialized by one lock (the same pattern as the libghostty-vt engine): the calls are µs each, and no handle
 * can be opened behind [close]'s back.
 */
class WindowsMemoryReader : ProcessMemoryReader {
    private val lock = Any()

    // The open process handles, by pid; opened by the first read of a pid, dropped by [release].
    private val handles = HashMap<Long, MemorySegment>()

    @Volatile
    private var closed = false

    // startInstant is not needed: the open handle pins the process, so the pid cannot point to another process
    // while the reader keeps it.
    override fun read(pid: Long, startInstant: Instant?): ProcessMemory? =
        synchronized(lock) { liveHandle(pid)?.let(Kernel32::memoryOf) }

    override fun detail(pid: Long): Map<String, Long> =
        synchronized(lock) { liveHandle(pid)?.let(Kernel32::detailOf) }.orEmpty()

    override fun release(pid: Long) {
        synchronized(lock) { releaseLocked(pid) }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            handles.values.forEach(Kernel32::closeHandle)
            handles.clear()
        }
    }

    // The open handle of a running process, opened on first use; null when it is gone. The caller holds the lock.
    private fun liveHandle(pid: Long): MemorySegment? {
        val handle = if (closed) null else handles[pid] ?: Kernel32.openProcess(pid)?.also { handles[pid] = it }
        val exited = handle != null && Kernel32.hasExited(handle)
        if (exited) releaseLocked(pid)
        return handle.takeUnless { exited }
    }

    // The caller holds the lock.
    private fun releaseLocked(pid: Long) {
        handles.remove(pid)?.let(Kernel32::closeHandle)
    }
}

/** kernel32 through the Foreign Function & Memory API, in the style of the ghostty bindings. */
private object Kernel32 {
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val FALSE = 0
    private const val STILL_ACTIVE = 259
    private const val WORKING_SET = "WorkingSetSize"
    private const val PEAK_WORKING_SET = "PeakWorkingSetSize"
    private const val PRIVATE_USAGE = "PrivateUsage"

    private val linker = Linker.nativeLinker()

    // A shared arena that is never closed: kernel32 is loaded in every Windows process anyway, and the
    // lookup must outlive the reader, whose owner decides when it is done.
    private val symbols = SymbolLookup.libraryLookup("kernel32", Arena.ofShared())

    private val openProcessCall = function("OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))
    private val getExitCodeCall = function("GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val getMemoryInfoCall =
        function("K32GetProcessMemoryInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    private val closeHandleCall = function("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    // PROCESS_MEMORY_COUNTERS_EX: `cb` and `PageFaultCount` as DWORDs, then 9 SIZE_T fields, the last of
    // which (`PrivateUsage`) is what the EX version adds. `cb` must be set to the struct size before the
    // call, or the API answers the smaller layout without the private memory.
    private val counters =
        MemoryLayout.structLayout(
            JAVA_INT.withName("cb"),
            JAVA_INT.withName("PageFaultCount"),
            JAVA_LONG.withName(PEAK_WORKING_SET),
            JAVA_LONG.withName(WORKING_SET),
            JAVA_LONG.withName("QuotaPeakPagedPoolUsage"),
            JAVA_LONG.withName("QuotaPagedPoolUsage"),
            JAVA_LONG.withName("QuotaPeakNonPagedPoolUsage"),
            JAVA_LONG.withName("QuotaNonPagedPoolUsage"),
            JAVA_LONG.withName("PagefileUsage"),
            JAVA_LONG.withName("PeakPagefileUsage"),
            JAVA_LONG.withName(PRIVATE_USAGE),
        )
    private val countersSize = counters.byteSize().toInt()
    private val workingSetOffset = counters.byteOffset(MemoryLayout.PathElement.groupElement(WORKING_SET))
    private val peakWorkingSetOffset = counters.byteOffset(MemoryLayout.PathElement.groupElement(PEAK_WORKING_SET))
    private val privateUsageOffset = counters.byteOffset(MemoryLayout.PathElement.groupElement(PRIVATE_USAGE))

    private fun function(name: String, descriptor: FunctionDescriptor) =
        linker.downcallHandle(
            symbols.find(name).orElseThrow { IllegalStateException("kernel32 has no $name") },
            descriptor,
        )

    /** Opens a query handle for [pid], or null when it is gone or not allowed. */
    fun openProcess(pid: Long): MemorySegment? {
        val handle =
            openProcessCall.invokeWithArguments(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid.toInt()) as MemorySegment
        return if (handle == MemorySegment.NULL) null else handle
    }

    /** Whether the process behind [handle] has exited; a failed query counts as exited (its handle is bad). */
    fun hasExited(handle: MemorySegment): Boolean =
        Arena.ofConfined().use { arena ->
            val exitCode = arena.allocate(JAVA_INT)
            val answered = getExitCodeCall.invokeWithArguments(handle, exitCode) as Int
            answered == FALSE || exitCode.get(JAVA_INT, 0) != STILL_ACTIVE
        }

    /** The memory of the process behind [handle], or null when the query fails. */
    fun memoryOf(handle: MemorySegment): ProcessMemory? =
        withCounters(handle) { counters ->
            ProcessMemory(counters.get(JAVA_LONG, privateUsageOffset), counters.get(JAVA_LONG, workingSetOffset))
        }

    /** The detail breakdown of the process behind [handle], or null when the query fails. */
    fun detailOf(handle: MemorySegment): Map<String, Long>? =
        withCounters(handle) { counters ->
            mapOf(
                PRIVATE_USAGE to counters.get(JAVA_LONG, privateUsageOffset),
                WORKING_SET to counters.get(JAVA_LONG, workingSetOffset),
                PEAK_WORKING_SET to counters.get(JAVA_LONG, peakWorkingSetOffset),
            )
        }

    /** Closes a handle; a failure changes nothing for the caller (the handle was done anyway). */
    fun closeHandle(handle: MemorySegment) {
        closeHandleCall.invokeWithArguments(handle)
    }

    // Reads the counters of [handle] into a scratch arena and maps them with [read]; null when it fails.
    private inline fun <T> withCounters(handle: MemorySegment, read: (MemorySegment) -> T): T? =
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(counters)
            info.set(JAVA_INT, 0, countersSize)
            val answered = getMemoryInfoCall.invokeWithArguments(handle, info, countersSize) as Int
            if (answered == FALSE) null else read(info)
        }
}
