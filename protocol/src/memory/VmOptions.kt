package org.example.kotlineditor.memory

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory

/** The options of HotSpot that can be read and set while running; nothing to read nor set in another JVM. */
internal class VmOptions {
    // A native image has no HotSpot: no bean (or none it can give), and the options are left alone.
    private val diagnostics: HotSpotDiagnosticMXBean? =
        runCatching { ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java) }.getOrNull()

    fun get(name: String): String? =
        try {
            diagnostics?.getVMOption(name)?.value
        } catch (_: IllegalArgumentException) {
            null // Another collector than G1, another JVM than HotSpot.
        }

    fun set(name: String, value: Long) {
        try {
            diagnostics?.setVMOption(name, value.toString())
        } catch (_: IllegalArgumentException) {
            // Not a flag this JVM lets change: it keeps its heap as before.
        }
    }
}
