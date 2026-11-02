package org.example.kotlineditor.e2e

import java.nio.file.Path

// Git repositories for the e2e tests, driven with the git executable.

/** Runs git in the project with a test identity, and returns its output; fails if git does. */
fun SampleProject.git(vararg args: String): String = gitIn(root, *args)

/** Runs git in [directory] with a test identity, and returns its output; fails if git does. */
fun gitIn(directory: Path, vararg args: String): String {
    val process =
        ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    return output
}

/** Makes the project a repository whose first commit holds every file. */
fun SampleProject.commitAll() {
    git("init", "-q")
    // The app commits and pulls without the test's -c options: a runner has no identity, and Windows converts EOLs.
    git("config", "user.name", "Test")
    git("config", "user.email", "test@example.com")
    git("config", "core.autocrlf", "false")
    git("add", "-A")
    git("commit", "-q", "-m", "init")
}

/** Subject of the last commit. */
fun SampleProject.lastCommitSubject(): String = git("log", "-1", "--format=%s").trim()

/** Number of commits of the current branch. */
fun SampleProject.commitCount(): Int = git("rev-list", "--count", "HEAD").trim().toInt()
