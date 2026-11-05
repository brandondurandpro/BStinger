package org.example.kotlineditor.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** A Git repository in a temporary directory, for the tests of the Git client; [delete] removes it. */
internal class TestRepository(prefix: String = "git-test") {
    val root: Path = Files.createTempDirectory(prefix)

    /** The root as the client takes it. */
    val path: String get() = root.toString()

    /** Runs Git in [directory] (the repository by default) and returns its output; fails the test when Git fails. */
    fun git(vararg args: String, directory: Path = root): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    fun write(name: String, text: String): Path {
        val file = root.resolve(name)
        file.parent?.let(Files::createDirectories)
        file.writeText(text)
        return file
    }

    /** Creates the repository with every file committed, with an identity and no conversion of line ends. */
    fun commitAll() {
        git("init", "-q")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", "init")
    }

    fun head(directory: Path = root): String = git("rev-parse", "HEAD", directory = directory).trim()

    fun delete() {
        root.toFile().deleteRecursively()
    }
}
