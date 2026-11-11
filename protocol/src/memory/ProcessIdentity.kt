package org.example.kotlineditor.memory

/**
 * What tells one process from every other: its [pid] and when it started, since a pid alone is reused, and is
 * the same number on two machines.
 */
data class ProcessIdentity(
    /** Process id. */
    val pid: Long,
    /** When the process started, in milliseconds since the epoch; 0 when the OS does not say. */
    val startedAtMillis: Long,
) {
    /** The identity of the running process. */
    companion object {
        /** This process. */
        fun current(): ProcessIdentity {
            val self = ProcessHandle.current()
            val started = self.info().startInstant().orElse(null)
            return ProcessIdentity(self.pid(), started?.toEpochMilli() ?: 0L)
        }
    }
}
