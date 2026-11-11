package org.example.kotlineditor.deploy.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.DeployDestination
import org.example.kotlineditor.model.DeployItem
import org.example.kotlineditor.model.DeployResult
import org.example.kotlineditor.model.FileEntry
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** What a deployment writes: where files land, what is kept, what is refused, on the disk and over SSH. */
@OptIn(ExperimentalPathApi::class)
class LocalDeployerTest {
    private val base = Files.createTempDirectory("local-deployer")
    private val project = base.resolve("project").createDirectories()
    private val target = base.resolve("target")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun entryOf(path: Path) = FileEntry(path.toString(), path.name, Files.isDirectory(path))

    private fun write(relative: String, text: String): Path {
        val file = project.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    // Each source lands at its own path in the project, as the tree's Deploy to… does.
    private fun deploy(
        sources: List<Path>,
        destination: DeployDestination,
        deployer: LocalDeployer = LocalDeployer(),
    ): DeployResult =
        deployItems(sources.map { it to project.relativize(it).toString().replace('\\', '/') }, destination, deployer)

    // Each source with the path where it lands under the destination.
    private fun deployItems(
        items: List<Pair<Path, String>>,
        destination: DeployDestination,
        deployer: LocalDeployer = LocalDeployer(),
    ): DeployResult =
        runBlocking {
            deployer.deploy(
                entryOf(project),
                items.map { (path, target) -> DeployItem(entryOf(path), target) },
                destination,
            )
        }

    @Test
    fun `a directory lands in the destination with its place relative to the project`() {
        write("web/index.html", "<html>")
        write("web/css/site.css", "body {}")
        write("README.md", "not deployed")
        val result = deploy(listOf(project.resolve("web")), DeployDestination(target.toString()))
        assertEquals(DeployResult(2, "<html>".length + "body {}".length.toLong()), result)
        assertEquals("<html>", target.resolve("web/index.html").readText())
        assertEquals("body {}", target.resolve("web/css/site.css").readText())
        assertFalse(target.resolve("README.md").exists())
    }

    @Test
    fun `a file and a directory are deployed together, the whole project when it is the source`() {
        write("a.txt", "a")
        write("dir/b.txt", "b")
        deploy(listOf(project.resolve("a.txt"), project.resolve("dir")), DeployDestination(target.toString()))
        assertEquals("a", target.resolve("a.txt").readText())
        assertEquals("b", target.resolve("dir/b.txt").readText())
        val all = base.resolve("all")
        deploy(listOf(project), DeployDestination(all.toString()))
        assertEquals("b", all.resolve("dir/b.txt").readText())
    }

    @Test
    fun `existing files are replaced and the other files of the destination are left alone`() {
        write("web/index.html", "new")
        target.resolve("web").createDirectories()
        target.resolve("web/index.html").writeText("old")
        target.resolve("web/uploaded.png").writeText("user data")
        deploy(listOf(project.resolve("web")), DeployDestination(target.toString()))
        assertEquals("new", target.resolve("web/index.html").readText())
        assertEquals("user data", target.resolve("web/uploaded.png").readText())
    }

    // Makes [file] impossible to copy, as a file another program holds or deletes while it is deployed: locked on
    // Windows, unreadable elsewhere. Null when this machine cannot (root reads every file).
    private fun uncopyable(file: Path): AutoCloseable? {
        if (WINDOWS) {
            val channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
            channel.lock()
            return channel
        }
        Files.setPosixFilePermissions(file, emptySet())
        return if (Files.isReadable(file)) null else AutoCloseable { }
    }

    @Test
    fun `a file that cannot be copied keeps the copy already deployed, and leaves nothing behind`() {
        val source = write("web/index.html", "new")
        target.resolve("web").createDirectories()
        target.resolve("web/index.html").writeText("old")
        val held = uncopyable(source) ?: return
        held.use {
            assertFailsWith<IOException> { deploy(listOf(source), DeployDestination(target.toString())) }
        }
        assertEquals("old", target.resolve("web/index.html").readText())
        val left = Files.list(target.resolve("web")).use { files -> files.map { it.name }.toList() }
        assertEquals(listOf("index.html"), left)
    }

    @Test
    fun `git data is not deployed`() {
        write("web/index.html", "x")
        write("web/.git/config", "secret")
        val result = deploy(listOf(project.resolve("web")), DeployDestination(target.toString()))
        assertEquals(1, result.files)
        assertFalse(target.resolve("web/.git").exists())
    }

    @Test
    fun `the executable bit and the link of a file are kept`() {
        if (WINDOWS) return
        val script = write("bin/run.sh", "#!/bin/sh\n")
        Files.setPosixFilePermissions(script, PosixFilePermission.entries.toSet())
        Files.createSymbolicLink(project.resolve("bin/latest"), Path.of("run.sh"))
        deploy(listOf(project.resolve("bin")), DeployDestination(target.toString()))
        assertTrue(Files.isExecutable(target.resolve("bin/run.sh")))
        val link = target.resolve("bin/latest")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(Path.of("run.sh"), Files.readSymbolicLink(link))
    }

    @Test
    fun `a relative destination is relative to the project`() {
        write("web/index.html", "x")
        deploy(listOf(project.resolve("web")), DeployDestination("../elsewhere"))
        assertEquals("x", base.resolve("elsewhere/web/index.html").readText())
    }

    @Test
    fun `a source outside the project is refused and nothing is written`() {
        val outside = base.resolve("outside.txt")
        outside.writeText("x")
        val failure =
            assertFailsWith<IllegalArgumentException> {
                deploy(listOf(outside), DeployDestination("$target"))
            }
        assertContains(failure.message.orEmpty(), "outside the project")
        assertFalse(target.exists())
    }

    @Test
    fun `a destination inside a source is refused and nothing is written`() {
        write("web/index.html", "x")
        val inside = project.resolve("web/dist")
        val failure =
            assertFailsWith<IllegalArgumentException> {
                deploy(listOf(project.resolve("web")), DeployDestination(inside.toString()))
            }
        assertContains(failure.message.orEmpty(), "is inside")
        assertFalse(inside.exists(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `nothing to deploy is refused`() {
        assertFailsWith<IllegalArgumentException> { deploy(emptyList(), DeployDestination("$target")) }
    }

    // A stand-in for OpenSSH that runs the command of the host here, in [home] (the home of the SSH user).
    private fun fakeSsh(home: Path, body: String? = null): LocalDeployer {
        val script = base.resolve("fake-ssh.sh")
        val run = body ?: "cd '$home' && exec sh -c \"\$1\""
        script.writeText(
            """
            #!/bin/sh
            while [ "${'$'}1" != "--" ]; do shift; done
            shift
            echo "${'$'}1" > '${base.resolve("host.txt")}'
            shift
            $run
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return LocalDeployer(listOf(script.toString()))
    }

    @Test
    fun `files are sent to another machine through tar and ssh`() {
        if (WINDOWS) return
        val home = base.resolve("home").createDirectories()
        write("web/index.html", "<html>")
        write("web/.git/config", "secret")
        val deployer = fakeSsh(home)
        val result = deploy(listOf(project.resolve("web")), DeployDestination("site", "web@example.com"), deployer)
        assertEquals(1, result.files)
        assertEquals("<html>", home.resolve("site/web/index.html").readText())
        assertFalse(home.resolve("site/web/.git").exists())
        assertEquals("web@example.com", base.resolve("host.txt").readText().trim())
    }

    @Test
    fun `a remote folder with spaces, quotes and the home mark is created as named`() {
        if (WINDOWS) return
        val home = base.resolve("home").createDirectories()
        write("a.txt", "a")
        val deployer = fakeSsh(home)
        deploy(listOf(project.resolve("a.txt")), DeployDestination("it's here", "h"), deployer)
        assertEquals("a", home.resolve("it's here/a.txt").readText())
        val home1 = """mkdir -p -- "${'$'}HOME"/'x y' && tar -x -f - -C "${'$'}HOME"/'x y'"""
        assertEquals(home1, extractCommand("~/x y"))
        val home2 = """mkdir -p -- "${'$'}HOME" && tar -x -f - -C "${'$'}HOME""""
        assertEquals(home2, extractCommand("~"))
    }

    @Test
    fun `a failing ssh reports what it said`() {
        if (WINDOWS) return
        write("a.txt", "a")
        val deployer = fakeSsh(base, "echo 'Permission denied (publickey).' >&2; exit 255")
        val failure =
            assertFailsWith<IOException> {
                deploy(listOf(project.resolve("a.txt")), DeployDestination("x", "h"), deployer)
            }
        assertContains(failure.message.orEmpty(), "Permission denied")
        assertContains(failure.message.orEmpty(), "Deployment to h failed")
    }

    @Test
    fun `a directory lands at the path it is given, which can differ from its place in the project`() {
        write("src/main/App.kt", "app")
        write("src/main/util/Strings.kt", "strings")
        deployItems(listOf(project.resolve("src/main") to "app/code"), DeployDestination(target.toString()))
        assertEquals("app", target.resolve("app/code/App.kt").readText())
        assertEquals("strings", target.resolve("app/code/util/Strings.kt").readText())
        assertFalse(target.resolve("src").exists())
    }

    @Test
    fun `a directory can land in the destination itself and a file can be renamed`() {
        write("web/index.html", "home")
        write("conf/prod.ini", "prod")
        deployItems(
            listOf(project.resolve("web") to "", project.resolve("conf/prod.ini") to "etc/app.ini"),
            DeployDestination(target.toString()),
        )
        assertEquals("home", target.resolve("index.html").readText())
        assertEquals("prod", target.resolve("etc/app.ini").readText())
        assertFalse(target.resolve("web").exists())
    }

    @Test
    fun `two folders can land in the same place, the second replacing the first where they overlap`() {
        write("a/x.txt", "from a")
        write("a/only-a.txt", "a")
        write("b/x.txt", "from b")
        deployItems(
            listOf(project.resolve("a") to "out", project.resolve("b") to "out"),
            DeployDestination(target.toString()),
        )
        assertEquals("from b", target.resolve("out/x.txt").readText())
        assertEquals("a", target.resolve("out/only-a.txt").readText())
    }

    @Test
    fun `a path that leaves the destination is refused and nothing is written`() {
        write("web/index.html", "x")
        for (escape in listOf("../outside", "/etc", "a/../../b", "C:/windows")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    deployItems(listOf(project.resolve("web") to escape), DeployDestination(target.toString()))
                }
            assertContains(failure.message.orEmpty(), "must stay inside the destination")
        }
        assertFalse(target.exists())
    }

    @Test
    fun `the project can be deployed under a folder of the destination`() {
        write("web/index.html", "x")
        deployItems(listOf(project to "release"), DeployDestination(target.toString()))
        assertEquals("x", target.resolve("release/web/index.html").readText())
    }

    @Test
    fun `directories and files go to their own paths on another machine`() {
        if (WINDOWS) return
        val home = base.resolve("home").createDirectories()
        write("src/main/App.kt", "app")
        write("src/main/.git/config", "secret")
        write("conf/prod.ini", "prod")
        val script = write("bin/run.sh", "#!/bin/sh\n")
        script.toFile().setExecutable(true)
        val deployer = fakeSsh(home)
        val result =
            deployItems(
                listOf(
                    project.resolve("src/main") to "app/code",
                    project.resolve("conf/prod.ini") to "etc/app.ini",
                    script to "bin/start",
                ),
                DeployDestination("site", "web@example.com"),
                deployer,
            )
        assertEquals(3, result.files)
        assertEquals("app", home.resolve("site/app/code/App.kt").readText())
        assertFalse(home.resolve("site/app/code/.git").exists())
        assertEquals("prod", home.resolve("site/etc/app.ini").readText())
        assertTrue(Files.isExecutable(home.resolve("site/bin/start")))
        assertFalse(Files.isExecutable(home.resolve("site/etc/app.ini")))
    }

    @Test
    fun `a large directory is sent over ssh in several archives`() {
        if (WINDOWS) return
        val home = base.resolve("home").createDirectories()
        repeat(450) { write("many/file$it.txt", "$it") }
        val deployer = fakeSsh(home)
        val result = deployItems(listOf(project.resolve("many") to "out"), DeployDestination("site", "h"), deployer)
        assertEquals(450, result.files)
        assertEquals("449", home.resolve("site/out/file449.txt").readText())
        assertEquals("0", home.resolve("site/out/file0.txt").readText())
    }
}
