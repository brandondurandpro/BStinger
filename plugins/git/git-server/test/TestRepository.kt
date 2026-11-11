package org.example.kotlineditor.git.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** How long a Git command of a test may run before it is stopped and the test fails. */
internal const val TEST_GIT_SECONDS = 60L

/** A Git repository in a temporary directory, for the tests of the Git client; [delete] removes it. */
internal class TestRepository(prefix: String = "git-test") {
    val root: Path = Files.createTempDirectory(prefix)

    /** The root as the client takes it. */
    val path: String get() = root.toString()

    /**
     * Runs Git in [directory] (the repository by default) and returns its output; fails the test when Git fails, or
     * when it runs longer than [TEST_GIT_SECONDS]: a hung Git fails the test instead of blocking the whole run.
     */
    fun git(vararg args: String, directory: Path = root): String {
        val identity = listOf("-c", "user.name=Test", "-c", "user.email=test@example.com")
        val run = execGit(directory.toString(), identity + args, TEST_GIT_SECONDS)
        check(run.exitCode == 0) { "git ${args.joinToString(" ")} failed: ${run.errors}${run.text}" }
        return run.text
    }

    /** Whether Git, run in the repository with [args], succeeds; within [TEST_GIT_SECONDS] like [git]. */
    fun gitSucceeds(vararg args: String): Boolean = execGit(path, args.toList(), TEST_GIT_SECONDS).exitCode == 0

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
