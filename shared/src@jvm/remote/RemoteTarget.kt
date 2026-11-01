package org.example.kotlineditor.remote

/** Operating system and CPU of a remote server, as the bundle names it (`linux-x86_64`, `macos-aarch64`…). */
data class RemoteTarget(val os: String, val arch: String) {
    /** Id used in bundle names and remote install directories. */
    val id: String get() = "$os-$arch"

    val isWindows: Boolean get() = os == WINDOWS

    companion object {
        const val LINUX = "linux"
        const val MACOS = "macos"
        const val WINDOWS = "windows"

        /** Maps `uname -s` to an OS id, or null when the remote system is not supported. */
        fun osOf(uname: String): String? =
            when {
                uname.startsWith("Linux", ignoreCase = true) -> LINUX

                uname.startsWith("Darwin", ignoreCase = true) -> MACOS

                uname.startsWith("MINGW", ignoreCase = true) ||
                    uname.startsWith("MSYS", ignoreCase = true) ||
                    uname.startsWith("CYGWIN", ignoreCase = true) -> WINDOWS

                else -> null
            }

        /** Maps `uname -m` (or `PROCESSOR_ARCHITECTURE`) to an architecture id, or null when unsupported. */
        fun archOf(machine: String): String? =
            when (machine.lowercase()) {
                "x86_64", "amd64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> null
            }
    }
}
