package org.example.packaging

import java.io.File

internal enum class Os(
    /** Name of the system in the names of the deliverables. */
    val label: String,
    /** File of the icon in the format of the system, in the `packaging` directory of the module. */
    val icon: String,
) {
    LINUX("linux", "icon.png"),
    WINDOWS("windows", "icon.ico"),
    MAC("macos", "icon.icns"),
    ;

    companion object {
        /** The system the task runs on: jpackage only packages for it. */
        val current: Os =
            System.getProperty("os.name").lowercase().let {
                when {
                    it.contains("win") -> WINDOWS
                    it.contains("mac") -> MAC
                    else -> LINUX
                }
            }
    }
}

/** `x64` or `arm64`, as in the names of the deliverables. */
internal val arch: String =
    when (val it = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> it
    }

/** Runs [command] in the console of the task, and fails if it fails. */
internal fun run(command: List<String>) {
    val code = ProcessBuilder(command).inheritIO().start().waitFor()
    check(code == 0) { "${command.first()} failed (exit code $code)" }
}

/** True if the command [name] is on the PATH. */
internal fun isOnPath(name: String): Boolean =
    System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .any { File(it, name).canExecute() }
