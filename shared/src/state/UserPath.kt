package org.example.kotlineditor.state

import java.io.IOException
import java.nio.file.Path
import java.util.Base64

/** The PATH of the user, which a new shell reads: where the `ide` command must be to be found. */
interface UserPath {
    /** Whether [directory] is on the PATH of the user. */
    fun contains(directory: Path): Boolean

    /** Puts [directory] on the PATH of the user, for the shells opened afterwards. */
    fun add(directory: Path)
}

/**
 * The PATH of a Unix shell is set by the profile of the user, which the IDE does not edit: it only tells whether the
 * folder is there (as the IDE sees the PATH), and [add] does nothing.
 */
class ShellUserPath(private val entries: List<String>) : UserPath {
    override fun contains(directory: Path): Boolean = entries.any { it.trimEnd('/') == directory.toString() }

    override fun add(directory: Path) = Unit

    companion object {
        fun ofEnvironment(): ShellUserPath {
            val value = System.getenv("PATH").orEmpty()
            return ShellUserPath(value.split(java.io.File.pathSeparator).filter { it.isNotBlank() })
        }
    }
}

/**
 * The PATH of the user in the registry (HKCU\Environment), edited through PowerShell: no administrator rights. The
 * raw value is read and written, so that `%VAR%` entries are not expanded and lost, and never through `setx`, which
 * cuts a PATH at 1024 characters.
 */
class WindowsUserPath : UserPath {
    override fun contains(directory: Path): Boolean {
        val output = powershell("[Console]::Out.Write((${READ_RAW}))", directory)
        val entries = output.split(';').map { it.trim().trimEnd('\\') }
        return entries.any { it.equals(directory.toString().trimEnd('\\'), ignoreCase = true) }
    }

    override fun add(directory: Path) {
        powershell(ADD, directory)
    }

    private fun powershell(script: String, directory: Path): String {
        // Encoded: Java does not escape the quotes of an argument, and PowerShell would cut the script in pieces.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val command = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["KOTLIN_EDITOR_BIN"] = directory.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw IOException("PowerShell failed: ${output.trim()}")
        return output
    }

    private companion object {
        const val KEY = "[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', \$true)"
        const val READ_RAW = "$KEY.GetValue('Path', '', 'DoNotExpandEnvironmentNames')"
        val ADD =
            listOf(
                "\$key = $KEY",
                "\$raw = \$key.GetValue('Path', '', 'DoNotExpandEnvironmentNames')",
                "\$dir = \$env:KOTLIN_EDITOR_BIN",
                "if (-not (\$raw.Split(';') -contains \$dir)) {",
                "  \$value = if (\$raw) { \"\$raw;\$dir\" } else { \$dir }",
                "  \$key.SetValue('Path', \$value, 'ExpandString')",
                // Tells the shells already running that the environment changed.
                "  [Environment]::SetEnvironmentVariable('KOTLIN_EDITOR_NOTIFY', \$null, 'User')",
                "}",
            ).joinToString("\n")
    }
}
