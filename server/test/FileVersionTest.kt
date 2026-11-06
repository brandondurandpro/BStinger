package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FILE_CHANGED_MESSAGE
import org.example.kotlineditor.model.FileChangedException
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileVersion
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RpcClient
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** The version of the files, checked on the disk when a save is asked with the one it read. */
@OptIn(ExperimentalPathApi::class)
class FileVersionTest {
    private val base = Files.createTempDirectory("file-version")
    private val project = Files.createDirectories(base.resolve("project"))

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

    @Test
    fun `a read answers the version of the file, twice the same, and another one once it changed`() =
        runBlocking {
            val file = project.resolve("read.txt")
            file.writeText("one\ntwo\n")
            val first = textVersionOf(file)
            assertEquals(first, textVersionOf(file), "Two reads of the same file, one version")
            file.writeText("three\n")
            val second = textVersionOf(file)
            assertFalse(first == second, "A file changed gets another version")
            assertEquals(Files.size(file), second.size)
        }

    @Test
    fun `a binary file carries a version too, one too large or failed carries none`() =
        runBlocking {
            val binary = project.resolve("image.bin")
            binary.writeBytes("picture\u0000picture".toByteArray())
            val read = fs.read(entryOf(binary))
            assertTrue(read is FileContent.Binary)
            assertNotNull(read.version, "The version of a binary file is known: its bytes were read")
            val small = LocalFileSystem(maxFileSize = 4)
            val tooLarge = small.read(entryOf(binary))
            assertTrue(tooLarge is FileContent.TooLarge)
            assertNull(tooLarge.version, "The file was not read, so it was not hashed")
            val failing = fs.read(entryOf(project.resolve("gone.txt")))
            assertTrue(failing is FileContent.Error)
            assertNull(failing.version)
        }

    @Test
    fun `a write checked with the version read writes, and answers the new version`() =
        runBlocking {
            val file = project.resolve("write.txt")
            file.writeText("one\n")
            val read = textVersionOf(file)
            val written = assertNotNull(fs.write(entryOf(file), "two\n", expected = read))
            assertEquals("two\n", file.readText())
            val again = textVersionOf(file)
            assertEquals(again, written, "The write answers the version of the file it left on the disk")
            assertFalse(again == read)
        }

    @Test
    fun `a write checked with a stale version fails and leaves the file as it is`() =
        runBlocking {
            val file = project.resolve("stale.txt")
            file.writeText("read\n")
            val read = textVersionOf(file)
            file.writeText("changed outside\n")
            val failure =
                assertFailsWith<FileChangedException> { fs.write(entryOf(file), "written\n", expected = read) }
            assertEquals(FILE_CHANGED_MESSAGE, failure.message)
            assertEquals("changed outside\n", file.readText())
        }

    @Test
    fun `a write replayed with the bytes the disk already holds succeeds`() =
        runBlocking {
            val file = project.resolve("replay.txt")
            file.writeText("read\n")
            val read = textVersionOf(file)
            // The first write landed, but its answer was lost, so it was sent again, with the version it had read.
            fs.write(entryOf(file), "written\n", expected = null)
            fs.write(entryOf(file), "written\n", expected = read)
            assertEquals(
                "written\n",
                file.readText(),
                "The replay finds its own bytes, and passes instead of a conflict",
            )
        }

    @Test
    fun `a write that has nothing to check against creates the file`() =
        runBlocking {
            val file = project.resolve("new.txt")
            // The file was gone when its version was asked for (a program deleted it): the check is skipped and
            // the write creates it, like every write has always done there.
            val written = fs.write(entryOf(file), "created\n", expected = FileVersion(0L, 0L, null, "00"))
            assertNotNull(written)
            assertEquals("created\n", file.readText())
        }

    @Test
    fun `an unchecked write writes as before, checked or not by the caller`() =
        runBlocking {
            val file = project.resolve("default.txt")
            fs.write(entryOf(file), "one\n")
            assertEquals("one\n", file.readText())
            fs.write(entryOf(file), "two\n", expected = null)
            assertEquals("two\n", file.readText())
            // Even unchecked, the write answers the version it left on the disk.
            val bytes = Files.readAllBytes(file)
            val written = assertNotNull(fs.write(entryOf(file), "two\n"))
            assertEquals(sha256Hex(bytes), written.sha256)
        }

    @Test
    fun `a symbolic link gives and keeps the version of its target`() =
        runBlocking {
            val target = project.resolve("target.txt")
            target.writeText("target\n")
            val link = project.resolve("link.txt")
            if (linked(link, target)) {
                val readThroughLink = textVersionOf(link)
                assertEquals(
                    readThroughLink,
                    textVersionOf(target),
                    "Reading through the link, the version of the target",
                )
                val written = fs.write(entryOf(link), "written\n", expected = readThroughLink)
                assertEquals("written\n", target.readText(), "Written through the link, to the file it points to")
                assertEquals("written\n", link.readText())
                assertTrue(Files.isSymbolicLink(link), "Still a link, not a copy")
                assertEquals(written, textVersionOf(target), "The version written, the one of the file written")
            }
        }

    @Test
    fun `an ISO-8859-1 file roundtrips byte for byte, its version hashing those bytes`() =
        runBlocking {
            val file = project.resolve("latin-1.txt")
            val bytes = "café\n".toByteArray(Charsets.ISO_8859_1)
            file.writeBytes(bytes)
            val read = fileTextOf(file)
            val version = assertNotNull(read.version)
            assertEquals(sha256Hex(bytes), version.sha256, "The hash of the bytes, whatever the encoding")

            val text = read.lines.joinToString(read.lineSeparator)
            val saved = assertNotNull(fs.write(entryOf(file), text, expected = read.version))
            assertTrue(Files.readAllBytes(file).contentEquals(bytes), "Every byte kept")
            assertEquals(Files.size(file), saved.size)

            // The file is then touched only (still the same bytes): saving the same text again is a replay,
            // not a conflict.
            Files.setLastModifiedTime(file, FileTime.fromMillis(0L))
            val touched = fileTextOf(file)
            fs.write(entryOf(file), text, expected = assertNotNull(touched.version))
            assertTrue(Files.readAllBytes(file).contentEquals(bytes))
        }

    @Test
    fun `a write dropped by the write ordering is never checked`() =
        runBlocking {
            val file = project.resolve("order.txt")
            file.writeText("start\n")
            val stale = textVersionOf(file)
            file.writeText("newest\n")
            // The older write (order 1) arrives after a newer one (order 2) of the same writer: dropped, checking
            // nothing, not against its stale version either, nor answering one.
            fs.write(entryOf(file), "newer\n", writer = "client", order = 2, expected = null)
            val dropped = fs.write(entryOf(file), "older\n", writer = "client", order = 1, expected = stale)
            assertNull(dropped, "A dropped write answers no version")
            assertEquals("newer\n", file.readText())
        }

    @Test
    fun `the file key is set where the disk has one, and nothing fails whatever the name`() =
        runBlocking {
            val names =
                listOf(
                    "plain.txt",
                    "with spaces.txt",
                    "ünïcødé-étrange.txt",
                    "with#hash&and[brackets].txt",
                )
            for (name in names) {
                val file = project.resolve(name)
                file.writeText("named\n")
                val version = textVersionOf(file)
                if (hasUnixView(file)) {
                    assertEquals(
                        Files.getAttribute(file, "unix:ino").toString(),
                        version.fileKey,
                        "The inode of $name",
                    )
                }
            }
            // On a disk without a unix view (Windows), no name carries a file key: the hash decides there.
            if (!hasUnixView(project)) {
                assertNull(textVersionOf(project.resolve("plain.txt")).fileKey)
            }
        }

    private fun hasUnixView(file: Path): Boolean =
        Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null

    private suspend fun fileTextOf(file: Path): FileContent.Text = fs.read(entryOf(file)) as FileContent.Text

    private suspend fun textVersionOf(file: Path): FileVersion {
        val text = fileTextOf(file)
        return assertNotNull(checkNotNull(text.version) { "The version of the text file read" })
    }
}

/** The versions checked where the client goes through the protocol: `fs/read` answers it, `fs/write` asks for it. */
@OptIn(ExperimentalPathApi::class)
class FileVersionProtocolTest {
    private val base = Files.createTempDirectory("file-version-protocol")
    private val project = Files.createDirectories(base.resolve("project"))
    private val server = EditorServer(LocalServices(base.toFile())).start()
    private val client = RpcClient("127.0.0.1", server.port, server.token)
    private val fs = RemoteFileSystem(client)

    @AfterTest
    fun cleanup() {
        client.close()
        server.close()
        base.deleteRecursively()
    }

    private fun entryOf(path: Path): FileEntry = FileEntry(path.toString(), path.name, Files.isDirectory(path))

    @Test
    fun `a version read travels, and is checked when the save is written`() =
        runBlocking {
            val file = project.resolve("remote.txt")
            file.writeText("one\n")
            val entry = entryOf(file)
            val read = fs.read(entry) as FileContent.Text
            val version = assertNotNull(read.version)
            // A save with the version it read writes, and answers the one now on the disk.
            val written = fs.write(entry, "two\n", expected = version)
            assertEquals("two\n", file.readText())
            val again = assertNotNull((fs.read(entry) as FileContent.Text).version)
            assertEquals(again, written)

            // Changed outside the IDE since the first read: the save is refused, the file keeps its bytes.
            file.writeText("changed outside\n")
            val error = assertFailsWith<FileChangedException> { fs.write(entry, "third\n", expected = version) }
            assertTrue(FILE_CHANGED_MESSAGE in error.message.orEmpty(), "The conflict is visible on the client")
            assertEquals("changed outside\n", file.readText())
        }
}
