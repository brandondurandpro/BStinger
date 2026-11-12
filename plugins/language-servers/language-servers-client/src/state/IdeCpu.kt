package org.example.kotlineditor.state

import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.model.ServerMemory

/**
 * The processor the IDE uses on its machines, in percent of one core as `top` counts it (a process busy on two cores
 * is at 200). Null where it cannot be measured, or before the first measure.
 */
data class IdeCpu(
    /** Processor use of the process of the UI. */
    val uiPercent: Int?,
    /** Processor use of the process of the IDE server; the language servers are other processes, not counted. */
    val serverPercent: Int?,
    /**
     * Whether the server runs in the process of the UI: [uiPercent] and [serverPercent] then measure the same process,
     * which [totalPercent] counts once.
     */
    val sameProcess: Boolean,
) {
    /** Processor use of both, null when one of them is unknown. */
    val totalPercent: Int?
        get() {
            if (sameProcess) return uiPercent ?: serverPercent
            if (uiPercent == null || serverPercent == null) return null
            return uiPercent + serverPercent
        }
}

/** The processor use of the UI, last read as [uiPercent], and of the server that sent [server]. */
fun ideCpu(ui: ProcessIdentity, uiPercent: Int?, server: ServerMemory): IdeCpu {
    val serverProcess = ProcessIdentity(server.pid, server.startedAtMillis)
    val sameProcess = server.pid != 0L && serverProcess == ui
    val measured = server.cpuPercent.takeIf { it != ServerMemory.NOT_MEASURED }
    return IdeCpu(uiPercent = uiPercent, serverPercent = measured, sameProcess = sameProcess)
}

/** A processor use for the status bar: `7%`. */
fun cpuText(percent: Int): String = "$percent%"
