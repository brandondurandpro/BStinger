package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.syntax.DeclarationKind
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `git branch is read from HEAD`() =
        runBlocking {
            File(root.toFile(), ".git").mkdirs()
            File(root.toFile(), ".git/HEAD").writeText("ref: refs/heads/feature/x\n")
            assertEquals("feature/x", CliGitClient().branch(root.toString()))
            File(root.toFile(), ".git/HEAD").writeText("0123456789abcdef\n")
            assertEquals("01234567", CliGitClient().branch(root.toString()))
            assertNull(CliGitClient().branch(root.resolve("nowhere").toString()))
        }
}
