package org.example.kotlineditor.state

import java.io.File
import kotlin.jvm.optionals.getOrNull

/**
 * The other IDEs that run from the same install: nothing enforces a single one (the `ide` command starts a new
 * process for each folder), and swapping the install under a running IDE would break it. An update only installs
 * once they are closed.
 */
internal object RunningInstances {
    /** How many processes run a program of the install of [target], besides this IDE and its own family. */
    fun others(target: UpdateTarget): Int {
        val own = ProcessHandle.current()
        val family = familyOf(own)
        val caseSensitive = UpdateOs.fromName(System.getProperty("os.name").orEmpty()) != UpdateOs.WINDOWS
        val commands =
            ProcessHandle
                .allProcesses()
                .filter { it.pid() !in family }
                .map { it.info().command().getOrNull() }
                .toList()
        return commands.count { command -> command != null && isFromInstall(command, target.installed, caseSensitive) }
    }

    /**
     * Whether the program [command] is the [installed] file (an AppImage) or a file inside the [installed] directory
     * (the launcher, or the Java of its runtime).
     */
    fun isFromInstall(command: String, installed: File, caseSensitive: Boolean): Boolean {
        val program = normalized(command, caseSensitive)
        val install = normalized(installed.path, caseSensitive)
        val inside = program.startsWith(install + "/")
        return program == install || inside
    }

    private fun normalized(path: String, caseSensitive: Boolean): String {
        val slashes = File(path).absolutePath.replace('\\', '/').trimEnd('/')
        return if (caseSensitive) slashes else slashes.lowercase()
    }

    // This process, its ancestors (the launcher of jpackage on Windows, the runtime of an AppImage) and descendants.
    private fun familyOf(own: ProcessHandle): Set<Long> {
        val family = HashSet<Long>()
        family.add(own.pid())
        own.descendants().forEach { family.add(it.pid()) }
        var ancestor = own.parent().getOrNull()
        while (ancestor != null) {
            family.add(ancestor.pid())
            ancestor = ancestor.parent().getOrNull()
        }
        return family
    }
}
