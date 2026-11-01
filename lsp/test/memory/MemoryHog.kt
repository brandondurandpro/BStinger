package org.example.kotlineditor.lsp.memory

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val CHUNK = 16 * 1024 * 1024
private const val PAGE = 4096

/**
 * A process that holds a given amount of private memory, for the sampler tests: `<mb> [childMb]`. It allocates
 * direct buffers and writes one byte per page, because untouched memory never becomes resident. With a child
 * amount it first starts a child holding that much. It prints `ready` once everything is resident, then waits for
 * its stdin to close (the test ending), and only then exits, taking its child with it.
 */
object MemoryHog {
    private const val STOP_SECONDS = 5L

    @JvmStatic
    fun main(args: Array<String>) {
        val held = hold(args[0].toInt())
        val child = args.getOrNull(1)?.let { start(it.toInt()) }
        println("ready ${held.size}")
        System.out.flush()
        System.`in`.read()
        child?.destroyForcibly()
    }

    private fun hold(megabytes: Int): List<ByteBuffer> =
        (0 until megabytes * 1024 * 1024 / CHUNK).map {
            ByteBuffer.allocateDirect(CHUNK).also { buffer ->
                for (index in 0 until CHUNK step PAGE) buffer.put(index, 1)
            }
        }

    /** Starts a hog of [megabytes] as a child of this JVM and waits until it is ready. */
    fun start(megabytes: Int, childMegabytes: Int? = null): Process {
        val args = listOfNotNull(megabytes.toString(), childMegabytes?.toString())
        val process =
            ProcessBuilder(javaCommand(MemoryHog::class.java.name, args))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        val line = BufferedReader(InputStreamReader(process.inputStream)).readLine()
        check(line?.startsWith("ready") == true) { "The memory hog did not start: $line" }
        return process
    }

    // `java @argfile <main> <args>` with the classpath of this JVM. The classpath goes through an argument file:
    // it is longer than the Windows command line allows.
    private fun javaCommand(mainClass: String, args: List<String>): List<String> {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val classpath = System.getProperty("java.class.path").replace("""\""", """\\""")
        val argFile = Files.createTempFile("memory-hog", ".args")
        argFile.toFile().deleteOnExit()
        Files.write(argFile, listOf("-XX:MaxDirectMemorySize=2g", "-Xmx64m", "-cp", "\"$classpath\""))
        return listOf(java, "@$argFile", mainClass) + args
    }

    /** Ends a hog started by [start]: closing its stdin lets it exit, the destroy covers a stuck one. */
    fun stop(process: Process) {
        val children = process.descendants().toList()
        runCatching { process.outputStream.close() }
        if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        children.forEach { it.destroyForcibly() }
    }
}
