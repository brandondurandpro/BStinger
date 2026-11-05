package org.example.kotlineditor.server

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RpcClient
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LARGE_TEXT_CHARS = 20 * 1024 * 1024
private const val ORDERED_WRITES = 5

/** What the server's file system does to the disk: deletions, saves and their order. */
@OptIn(ExperimentalPathApi::class)
class LocalFileSystemTest {
    private val base = Files.createTempDirectory("local-file-system")
    private val project = Files.createDirectories(base.resolve("project"))
    private val outside = Files.createDirectories(base.resolve("outside"))
    private val fs = LocalFileSystem()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun entryOf(path: Path): FileEntry = FileEntry(path.toString(), path.name, Files.isDirectory(path))

    // A symbolic link at [link] to [target]; false where the disk or the user cannot make one (Windows without the
    // privilege), so that the test has nothing to check there.
    private fun linked(link: Path, target: Path): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        }

    private fun outsideFiles(): Path {
        outside.resolve("keep.txt").writeText("kept")
        Files.createDirectories(outside.resolve("nested")).resolve("deep.txt").writeText("deep")
        return outside
    }

    private fun assertOutsideKept() {
        assertEquals("kept", outside.resolve("keep.txt").readText())
        assertEquals("deep", outside.resolve("nested/deep.txt").readText())
    }

    @Test
    fun `deleting a directory deletes a link to a directory outside the project, not what it points to`() =
        runBlocking {
            val dir = Files.createDirectories(project.resolve("dir"))
            dir.resolve("own.txt").writeText("own")
            if (linked(dir.resolve("link"), outsideFiles())) {
                fs.delete(entryOf(dir))
                assertFalse(Files.exists(dir, LinkOption.NOFOLLOW_LINKS))
                assertOutsideKept()
            }
        }

    @Test
    fun `deleting a link to a directory deletes the link only`() =
        runBlocking {
            val link = project.resolve("vendor")
            if (linked(link, outsideFiles())) {
                fs.delete(FileEntry(link.toString(), link.name, true))
                assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
                assertOutsideKept()
            }
        }

    @Test
    fun `deleting a directory deletes a link to a file, not the file`() =
        runBlocking {
            val target = project.resolve("target.txt")
            target.writeText("target")
            val dir = Files.createDirectories(project.resolve("dir/sub"))
            if (linked(dir.resolve("link.txt"), target)) {
                fs.delete(entryOf(project.resolve("dir")))
                assertFalse(Files.exists(project.resolve("dir")))
                assertEquals("target", target.readText())
            }
        }

    @Test
    fun `deleting a directory deletes everything in it, and a missing entry is no error`() =
        runBlocking {
            val dir = Files.createDirectories(project.resolve("dir/a/b"))
            dir.resolve("c.txt").writeText("c")
            project.resolve("dir/top.txt").writeText("top")
            fs.delete(entryOf(project.resolve("dir")))
            assertFalse(Files.exists(project.resolve("dir")))
            fs.delete(FileEntry(project.resolve("gone").toString(), "gone", true))
            fs.delete(FileEntry(project.resolve("gone.txt").toString(), "gone.txt", false))
            assertTrue(Files.isDirectory(project))
        }

    @Test
    fun `two writes of one file sent in order land in that order, the first one the slower`() {
        val data = Files.createDirectories(base.resolve("data"))
        val server = EditorServer(LocalServices(data.toFile())).start()
        val client = RpcClient("127.0.0.1", server.port, server.token)
        try {
            val remote = RemoteFileSystem(client)
            val file = project.resolve("ordered.txt")
            file.writeText("start")
            val entry = entryOf(file)
            val large = "x".repeat(LARGE_TEXT_CHARS)
            val outOfOrder =
                (1..ORDERED_WRITES).count { round ->
                    runBlocking {
                        val first = async { remote.write(entry, large) }
                        val second = async { remote.write(entry, "short $round") }
                        first.await()
                        second.await()
                    }
                    file.readText() != "short $round"
                }
            assertEquals(0, outOfOrder, "Writes that landed out of order, out of $ORDERED_WRITES")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `a write that reaches a file after a newer one of the same writer is dropped`() =
        runBlocking {
            val file = project.resolve("late.txt")
            file.writeText("start")
            val entry = entryOf(file)
            fs.write(entry, "newer", writer = "client", order = 2)
            fs.write(entry, "older", writer = "client", order = 1)
            assertEquals("newer", file.readText())
            // Another client's writes are not ordered with this one's.
            fs.write(entry, "another client", writer = "other", order = 1)
            assertEquals("another client", file.readText())
            fs.write(entry, "newest", writer = "client", order = 3)
            assertEquals("newest", file.readText())
        }

    // Records when each step of a save makes it durable, with what the file holds at that moment.
    private class RecordingSync(private val file: Path) : DiskSync {
        val steps = ArrayList<String>()

        override fun file(channel: FileChannel) {
            steps += "file synced, the file holds '${file.readText()}'"
            DurableSync.file(channel)
        }

        override fun directory(path: Path) {
            // The real path of the directory: on Windows, the long name of a temp directory given in its 8.3 form.
            val parent = if (Files.isSameFile(path, file.parent)) "its directory" else path.toString()
            steps += "$parent synced, the file holds '${file.readText()}'"
            DurableSync.directory(path)
        }
    }

    @Test
    fun `a save is on the disk before it replaces the file, and the move is synced after it`() =
        runBlocking {
            val file = project.resolve("durable.txt")
            file.writeText("old")
            val sync = RecordingSync(file)
            writeFile(file, "new".toByteArray(), sync)
            assertEquals("new", file.readText())
            val expected =
                listOf("file synced, the file holds 'old'", "its directory synced, the file holds 'new'")
            assertEquals(expected, sync.steps)
        }

    @Test
    fun `a file written in place or created is synced too`() =
        runBlocking {
            val created = project.resolve("created.txt")
            val createdSync = RecordingSync(created)
            writeFile(created, "first".toByteArray(), createdSync)
            val expected =
                listOf("file synced, the file holds 'first'", "its directory synced, the file holds 'first'")
            assertEquals(expected, createdSync.steps)
            // A second hard link is kept by writing in place (where the disk has them).
            if ("unix" in project.fileSystem.supportedFileAttributeViews()) {
                Files.createLink(project.resolve("twin.txt"), created)
                val inPlaceSync = RecordingSync(created)
                writeFile(created, "second".toByteArray(), inPlaceSync)
                assertEquals("second", project.resolve("twin.txt").readText())
                assertEquals(listOf("file synced, the file holds 'second'"), inPlaceSync.steps)
            }
        }
}
