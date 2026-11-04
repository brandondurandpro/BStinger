package org.example.kotlineditor.testkit.acp

import kotlin.system.exitProcess

private const val FOREVER_LINE_MS = 50L

/**
 * A command for the [FakeAgent] to run in a terminal, the same on every platform: `lines <n> <exit code>` prints
 * `line 1` to `line n` then exits with the code; `forever` prints a line every 50 ms until it is killed.
 */
object FakeProgram {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "forever") {
            var line = 0
            while (true) {
                println("tick ${++line}")
                System.out.flush()
                Thread.sleep(FOREVER_LINE_MS)
            }
        }
        val count = args.getOrNull(1)?.toIntOrNull() ?: 1
        val code = args.getOrNull(2)?.toIntOrNull() ?: 0
        val out = System.out.bufferedWriter()
        for (line in 1..count) out.write("line $line\n")
        out.flush()
        if (code != 0) System.err.println("failed with $code")
        exitProcess(code)
    }
}
