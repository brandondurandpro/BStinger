package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.BlameLine
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOT_COMMITTED = "00000000"

/** The blames the Git client keeps: by content and HEAD, the oldest forgotten first. */
class GitBlameCacheTest {
    private val repository = TestRepository("git-blame")

    @AfterTest
    fun cleanup() {
        repository.delete()
    }

    @Test
    fun `an edit that keeps the size and the modification time is blamed again`() =
        runBlocking {
            val file = repository.write("a.kt", "aaa\n")
            repository.commitAll()
            val client = CliGitClient()
            val committed = client.blame(repository.path, "a.kt").single().commit
            assertEquals(repository.head().take(SHORT_HASH_LENGTH), committed)

            // A coarse clock, `rsync -t` or `touch -r`: same size, same time, other content.
            val modified = Files.getLastModifiedTime(file)
            repository.write("a.kt", "bbb\n")
            Files.setLastModifiedTime(file, modified)
            assertEquals(listOf(NOT_COMMITTED), client.blame(repository.path, "a.kt").map { it.commit })

            // Back to the committed content: blamed to its commit again.
            repository.write("a.kt", "aaa\n")
            Files.setLastModifiedTime(file, modified)
            assertEquals(listOf(committed), client.blame(repository.path, "a.kt").map { it.commit })
        }

    @Test
    fun `a new HEAD with the same content is blamed again, its refs loose or packed`() =
        runBlocking {
            repository.write("a.kt", "one\n")
            repository.commitAll()
            val client = CliGitClient()

            fun blamedTo() = runBlocking { client.blame(repository.path, "a.kt").single().commit }

            fun head() = repository.head().take(SHORT_HASH_LENGTH)
            assertEquals(head(), blamedTo())

            repository.git("commit", "-q", "--amend", "-m", "amended")
            assertEquals(head(), blamedTo(), "loose")

            repository.git("pack-refs", "--all")
            repository.git("commit", "-q", "--amend", "-m", "amended again")
            repository.git("pack-refs", "--all")
            assertEquals(head(), blamedTo(), "packed")
        }

    @Test
    fun `a file removed from the index loses its blame`() =
        runBlocking {
            repository.write("a.kt", "one\n")
            repository.write("b.kt", "two\n")
            repository.commitAll()
            val client = CliGitClient()
            assertEquals(1, client.blame(repository.path, "a.kt").size)
            // Another file staged: the index changed, the blame of a.kt still stands.
            repository.write("b.kt", "three\n")
            repository.git("add", "b.kt")
            assertEquals(1, client.blame(repository.path, "a.kt").size)

            repository.git("rm", "-q", "--cached", "a.kt")
            assertTrue(client.blame(repository.path, "a.kt").isEmpty())
        }

    @Test
    fun `the cache forgets the blame asked least recently, not every blame`() {
        val cache = BlameCache(capacity = 2)
        val index = IndexStamp(1, 1)
        val version = BlameCache.Version("head", "content")

        fun lines(name: String) = listOf(BlameLine(0, name, "Test", "2026-01-01"))
        cache.put("a", version, index, lines("a"))
        cache.put("b", version, index, lines("b"))
        // Asking for `a` makes `b` the oldest: the third blame pushes `b` out.
        assertEquals(lines("a"), cache.get("a", version, index) { true })
        cache.put("c", version, index, lines("c"))
        assertEquals(2, cache.size)
        assertNull(cache.get("b", version, index) { true })
        assertEquals(lines("a"), cache.get("a", version, index) { true })
        assertEquals(lines("c"), cache.get("c", version, index) { true })
        // Another content or HEAD is not the cached blame.
        assertNull(cache.get("a", version.copy(content = "edited"), index) { true })
        assertNull(cache.get("a", version.copy(head = "moved"), index) { true })
    }
}
