package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileEntry
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


/** What the server's file system does to the disk. */
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
}
