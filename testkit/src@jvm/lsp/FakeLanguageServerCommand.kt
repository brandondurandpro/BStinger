package org.example.kotlineditor.testkit.lsp

import java.nio.file.Files

/** Command lines that start a [FakeLanguageServer] in a new JVM, with the classpath of the running tests. */
object FakeLanguageServerCommand {
    /**
     * `java @argfile FakeLanguageServer <options>`. The classpath goes through a Java argument file, because it is
     * longer than a Windows command line allows; direct memory is capped high enough for the memory options.
     */
    fun command(vararg options: String): List<String> {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val classpath = System.getProperty("java.class.path").replace("""\""", """\\""")
        val argFile = Files.createTempFile("fake-language-server", ".args")
        argFile.toFile().deleteOnExit()
        Files.write(argFile, listOf("-XX:MaxDirectMemorySize=4g", "-Xmx1g", "-cp", "\"$classpath\""))
        return listOf(java, "@$argFile", FakeLanguageServer::class.java.name) + options
    }

    /** Starts a fake server with [options]; the caller destroys it (and [Process.descendants]) when done. */
    fun start(vararg options: String): Process = ProcessBuilder(command(*options)).start()
}
