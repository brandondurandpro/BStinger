package org.example.kotlineditor.state

import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.memory.ProcessMemory
import org.example.kotlineditor.memory.ProcessMemoryReader
import org.example.kotlineditor.model.ServerMemory
import java.io.IOException

/**
 * The process of the UI: which one it is, to tell whether the IDE server runs in it, and how its memory is read
 * ([memory] answers null when the OS does not let it be read).
 */
class UiProcess(
    /** The process the UI runs in. */
    val identity: ProcessIdentity,
    /** Its memory now, read from the OS. */
    val memory: () -> ProcessMemory?,
) {
    /** The process of the running UI. */
    companion object {
        /** This process, read with the reader of the OS (the one the server reads its language servers with). */
        fun current(): UiProcess {
            val identity = ProcessIdentity.current()
            val reader = ProcessMemoryReader.forCurrentOs()
            return UiProcess(identity) { readOrNull(reader, identity.pid) }
        }

        // No identity check: this pid is this process as long as it lives.
        private fun readOrNull(reader: ProcessMemoryReader, pid: Long): ProcessMemory? =
            try {
                reader.read(pid, null)
            } catch (_: RuntimeException) {
                null
            } catch (_: IOException) {
                null
            }
    }
}

/**
 * The memory the IDE takes on its machines, as the operating system counts it (the resident set of each process:
 * heap, code, native memory of the JVM, Skia...), not the heap alone. Null where it cannot be measured.
 */
data class IdeMemory(
    /** Resident memory of the process of the UI. */
    val uiBytes: Long?,
    /** Resident memory of the process of the IDE server; the language servers are other processes, not counted. */
    val serverBytes: Long?,
    /**
     * Whether the server runs in the process of the UI (no `java` launcher to start it apart): [uiBytes] and
     * [serverBytes] then measure the same process, which [totalBytes] counts once.
     */
    val sameProcess: Boolean,
) {
    /**
     * Memory of both, null when one of them is unknown. It adds what is shown of each, in whole megabytes, so that
     * the total on screen is the sum of the two numbers beside it.
     */
    val totalBytes: Long?
        get() {
            if (sameProcess) return uiBytes ?: serverBytes
            if (uiBytes == null || serverBytes == null) return null
            return wholeMegabytes(uiBytes) + wholeMegabytes(serverBytes)
        }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L

// [bytes] without the part of a megabyte that [memoryText] does not show.
private fun wholeMegabytes(bytes: Long): Long = bytes / BYTES_PER_MEGABYTE * BYTES_PER_MEGABYTE

/** The memory of the UI, last read as [uiMemory], and of the server that sent [server]. */
fun ideMemory(ui: ProcessIdentity, uiMemory: ProcessMemory?, server: ServerMemory): IdeMemory {
    val serverProcess = ProcessIdentity(server.pid, server.startedAtMillis)
    val sameProcess = server.pid != 0L && serverProcess == ui
    return IdeMemory(
        uiBytes = uiMemory?.residentBytes,
        serverBytes = server.residentBytes.takeIf { it > 0 },
        sameProcess = sameProcess,
    )
}
