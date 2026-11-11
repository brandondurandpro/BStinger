package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.syntax.DeclarationKind
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalServicesTest {
    private val root = Files.createTempDirectory("local-services")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `splits lines and detects CRLF and binary content`() {
        val text = TextFiles.splitLines("a\r\nb\r\n")
        assertEquals(listOf("a", "b", ""), text.lines)
        assertEquals("\r\n", text.lineSeparator)
        assertEquals("\n", TextFiles.splitLines("x\ny").lineSeparator)
        assertTrue(TextFiles.isBinary(byteArrayOf(1, 0, 2)))
        assertTrue(!TextFiles.isBinary("text".toByteArray()))
    }

    @Test
    fun `a file another process briefly holds open is still saved`() =
        runBlocking {
            val file = root.resolve("held.txt")
            Files.writeString(file, "old")
            val fs = LocalFileSystem()
            val entry = fs.fileAt(file.toString())!!
            // java.io opens without FILE_SHARE_DELETE on Windows, like a virus scanner: replacing the file fails.
            val reader = FileInputStream(file.toFile())
            val release =
                launch(Dispatchers.IO) {
                    delay(300)
                    reader.close()
                }
            fs.write(entry, "new")
            release.join()
            assertEquals("new", Files.readString(file))
        }

    @Test
    fun `a Latin-1 file is read and saved with its own bytes`() =
        runBlocking {
            val file = root.resolve("old.php")
            val bytes = "<?php // caf\u00e9\n".toByteArray(Charsets.ISO_8859_1)
            Files.write(file, bytes)
            val fs = LocalFileSystem()
            val entry = checkNotNull(fs.fileAt(file.toString()))
            val text = (fs.read(entry) as FileContent.Text).lines.joinToString("\n")
            assertEquals("<?php // caf\u00e9\n", text)
            fs.write(entry, text)
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
            // A character Latin-1 cannot hold fails the save rather than being replaced.
            assertFailsWith<java.io.IOException> { fs.write(entry, "<?php // \u20ac\n") }
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
        }

    @Test
    fun `saving through a symbolic link writes the file it points to`() =
        runBlocking {
            val target = root.resolve("target.txt")
            Files.writeString(target, "old")
            val link = root.resolve("link.txt")
            val linked = runCatching { Files.createSymbolicLink(link, target.fileName) }.isSuccess
            // Windows creates links only with a privilege the CI runner may lack.
            if (linked) {
                val fs = LocalFileSystem()
                fs.write(checkNotNull(fs.fileAt(link.toString())), "new")
                assertTrue(Files.isSymbolicLink(link))
                assertEquals("new", Files.readString(target))
            }
        }

    @Test
    fun `a saved file keeps its permissions`() =
        runBlocking {
            val file = root.resolve("run.sh")
            Files.writeString(file, "echo old")
            if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
                val fs = LocalFileSystem()
                fs.write(checkNotNull(fs.fileAt(file.toString())), "echo new")
                assertEquals("echo new", Files.readString(file))
                assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            }
        }

    @Test
    fun `a file whose path is longer than 260 characters is read and saved`() =
        runBlocking {
            val directory =
                (1..7).fold(
                    root,
                ) { parent, depth -> parent.resolve("a-directory-name-of-forty-characters-x$depth") }
            Files.createDirectories(directory)
            val file = directory.resolve("long.txt")
            Files.writeString(file, "first\n")
            assertTrue(file.toString().length > 260)
            val fs = LocalFileSystem()
            val entry = assertNotNull(fs.fileAt(file.toString()))
            assertIs<FileContent.Text>(fs.read(entry))
            fs.write(entry, "second\n")
            assertEquals("second\n", Files.readString(file))
        }

    @Test
    fun `a rename that only changes the case of the name renames the file, on any disk`() =
        runBlocking {
            Files.writeString(root.resolve("readme.md"), "text")
            val fs = LocalFileSystem()
            val entry = fs.list(fs.resolveDirectory(root.toString())!!).firstOrNull { it.name == "readme.md" }
            val renamed = fs.rename(assertNotNull(entry), "README.md")
            assertEquals("README.md", renamed.name)
            val names = Files.list(root).use { paths -> paths.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("README.md"), names)
            assertEquals("text", Files.readString(root.resolve("README.md")))
        }

    @Test
    fun `lists, reads, writes, walks and protects against escaping the directory`() =
        runBlocking {
            Files.createDirectories(root.resolve("b-dir/.git"))
            Files.writeString(root.resolve("b-dir/.git/HEAD"), "ref")
            Files.writeString(root.resolve("a.txt"), "1\n2")
            Files.writeString(root.resolve("B.txt"), "x")
            val fs = LocalFileSystem()
            val dir = fs.resolveDirectory(root.toString())!!
            assertEquals(listOf("b-dir", "a.txt", "B.txt"), fs.list(dir).map { it.name })
            val a = fs.list(dir).first { it.name == "a.txt" }
            assertEquals(listOf("1", "2"), (fs.read(a) as FileContent.Text).lines)
            fs.write(a, "new")
            assertEquals("new", Files.readString(root.resolve("a.txt")))
            assertEquals(setOf("a.txt", "B.txt"), LocalSearch(fs).walkFiles(dir).map { it.name }.toSet())
            assertNull(fs.resolveDirectory(root.resolve("a.txt").toString()))
            assertIs<FileContent.TooLarge>(LocalFileSystem(maxFileSize = 1).read(a))
            val escape = runCatching { fs.createFile(dir, "../outside.txt") }
            assertTrue(escape.isFailure)
        }

    @Test
    fun `find in files and symbols run on the server side`() =
        runBlocking {
            Files.writeString(
                root.resolve("A.kt"),
                """
                /* fun hidden() */
                class Alpha {
                    fun beta() = "needle"
                }
                """.trimIndent(),
            )
            Files.writeString(root.resolve("notes.md"), "no match\nNEEDLE here\n")
            val files = LocalFileSystem()
            val fs = LocalSearch(files)
            val dir = files.resolveDirectory(root.toString())!!
            val matches = fs.findInFiles(dir, "needle", ignoreCase = true, limit = 10)
            assertEquals(setOf("A.kt" to 2, "notes.md" to 1), matches.map { it.entry.name to it.line }.toSet())
            assertEquals(1, fs.findInFiles(dir, "needle", ignoreCase = false, limit = 10).size)
            val symbols = fs.symbols(dir).map { it.declaration.name to it.declaration.kind }
            assertEquals(listOf("Alpha" to DeclarationKind.Class, "beta" to DeclarationKind.Function), symbols)
        }

    @Test
    fun `sessions and recent projects survive a round trip on disk`() =
        runBlocking {
            val services = LocalServices(root.toFile())
            val session = ProjectSession(listOf(SessionFile("/p/a.kt", 3, 4)), "/p/a.kt")
            services.sessions.save("/p", session)
            assertEquals(session, LocalServices(root.toFile()).sessions.load("/p"))
            assertNull(services.sessions.load("/other"))
            services.recentProjects.save(listOf("/x", "/y"))
            assertEquals(listOf("/x", "/y"), LocalServices(root.toFile()).recentProjects.load())
        }
}
