package org.example.kotlineditor.remote

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The hosts of the user's `~/.ssh/config`, for the remote dialog's catalog.
 *
 * The aliases come from the config file and its `Include` directives; `ssh -G` then resolves the effective host
 * name, user, port, identity file and jump host exactly as an actual connection would (wildcards, Match blocks,
 * included files). `ssh -G` does not open a connection and needs no network.
 */
@Suppress(
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
) // a small, explicit parser for a line-oriented config format
object SshConfig {
    fun load(): List<SshConfigHost> {
        val configured = System.getProperty("kotlin.editor.ssh.config")
        return load(File(configured ?: File(System.getProperty("user.home"), ".ssh/config").path))
    }

    fun load(config: File): List<SshConfigHost> {
        if (!config.isFile) return emptyList()
        val aliases = runCatching { readAliases(config.toPath(), mutableSetOf()) }.getOrDefault(emptyList())
        if (aliases.isEmpty()) return emptyList()
        val ssh = System.getProperty("kotlin.editor.ssh", "ssh")
        return aliases.distinct().mapNotNull { alias -> resolve(ssh, config, alias) }
    }

    private fun resolve(ssh: String, config: File, alias: String): SshConfigHost? {
        val result =
            runCatching {
                val process =
                    ProcessBuilder(ssh, "-G", "-F", config.absolutePath, alias)
                        .redirectErrorStream(false)
                        .start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.use { it.readBytes() }
                if (process.exitValue() != 0) return null
                output
            }.getOrNull() ?: return null
        var hostName = alias
        var user: String? = null
        var port = 22
        var identity: String? = null
        var proxy: String? = null
        for (line in result.lineSequence()) {
            val key = line.substringBefore(' ').lowercase()
            val value = line.substringAfter(' ', "").trim()
            if (value.isEmpty()) continue
            when (key) {
                "hostname" -> hostName = value
                "user" -> user = value
                "port" -> port = value.toIntOrNull() ?: port
                "identityfile" -> identity = identity ?: value
                "proxyjump" -> proxy = value.takeUnless { it.equals("none", ignoreCase = true) }
            }
        }
        return SshConfigHost(alias, hostName, user, port, identity, proxy)
    }

    private fun readAliases(file: Path, seen: MutableSet<Path>): List<String> {
        val real =
            runCatching { file.toRealPath() }.getOrElse {
                // Missing or relative include: skip it rather than failing the whole catalog.
                return emptyList()
            }
        if (!seen.add(real) || !Files.isRegularFile(real)) return emptyList()
        val aliases = mutableListOf<String>()
        for (raw in Files.readAllLines(real)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+")).map(::unquote).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            when (parts.first().lowercase()) {
                "host" -> {
                    for (pattern in parts.drop(1)) {
                        if (isConcreteAlias(pattern)) aliases += pattern
                    }
                }

                "include" -> {
                    for (pattern in parts.drop(1)) {
                        aliases += readInclude(pattern, real.parent, seen)
                    }
                }
            }
        }
        return aliases
    }

    private fun readInclude(pattern: String, base: Path, seen: MutableSet<Path>): List<String> {
        val expanded = expandHome(pattern)
        val path = Path.of(expanded)
        val target = if (path.isAbsolute) path else base.resolve(path)
        val name = target.fileName?.toString().orEmpty()
        if ('*' !in name && '?' !in name) return readAliases(target, seen)
        val directory = target.parent ?: return emptyList()
        if (!Files.isDirectory(directory)) return emptyList()
        return runCatching {
            Files.newDirectoryStream(directory, name).use { stream ->
                stream.sortedBy { it.toString() }.flatMap { readAliases(it, seen) }
            }
        }.getOrDefault(emptyList())
    }

    private fun expandHome(value: String): String =
        if (value == "~" || value.startsWith("~/") || value.startsWith("~\\")) {
            System.getProperty("user.home") + value.substring(1)
        } else {
            value
        }

    private fun unquote(value: String): String = value.removeSurrounding("\"").removeSurrounding("'")

    private fun isConcreteAlias(alias: String): Boolean =
        alias.isNotEmpty() && '*' !in alias && '?' !in alias && '!' !in alias && !alias.startsWith("-")
}
