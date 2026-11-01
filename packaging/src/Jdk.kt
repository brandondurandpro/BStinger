package org.example.packaging

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** JDK that provides `jpackage`: the JRE running the task does not have it. */
internal class Jdk(private val home: Path) {
    /** Runs the tool [tool] of this JDK with [args]. */
    fun run(tool: String, args: List<Any>) {
        val executable = home.resolve("bin").resolve(if (Os.current == Os.WINDOWS) "$tool.exe" else tool)
        val command = listOf(executable.toString()) + args.map { it.toString() }
        println("[packaging] ${command.joinToString(" ")}")
        run(command)
    }

    /** Template `template.<name>` of jpackage for the Linux packages (`control`, `postinst`, `spec`…). */
    fun jpackageTemplate(name: String): String =
        FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to home.toString())).use {
            it.getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/template.$name").readText()
        }

    companion object {
        /** The JDK of `JAVA_HOME`, else of the PATH, else one the Toolchain downloaded, else a system one. */
        fun find(): Jdk {
            val executable = if (Os.current == Os.WINDOWS) "jpackage.exe" else "jpackage"
            val cache =
                System.getenv("KOTLIN_SHARED_CACHE_DIR")?.let { File(it) }
                    ?: File(System.getProperty("user.home"), ".cache/JetBrains/Kotlin")
            val candidates =
                buildList {
                    System.getenv("JAVA_HOME")?.let { add(Path.of(it)) }
                    System
                        .getenv("PATH")
                        .orEmpty()
                        .split(File.pathSeparator)
                        .filter { it.isNotBlank() }
                        .forEach { add(Path.of(it).parent ?: Path.of(it)) }
                    File(cache, "extract.cache").listFiles().orEmpty().forEach { dir ->
                        dir
                            .listFiles()
                            .orEmpty()
                            .filter { it.name.startsWith("jdk") }
                            .forEach { add(it.toPath()) }
                    }
                    File("/usr/lib/jvm").listFiles().orEmpty().forEach { add(it.toPath()) }
                    add(Path.of(System.getProperty("java.home")))
                }
            val home =
                candidates.firstOrNull { it.resolve("bin").resolve(executable).exists() }
                    ?: error("jpackage not found: install a full JDK and set JAVA_HOME")
            return Jdk(home)
        }
    }
}
