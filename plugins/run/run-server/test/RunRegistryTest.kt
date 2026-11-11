package org.example.kotlineditor.run.server

import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.runChunk
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RUN_ID = 7L
private const val TIMEOUT_MS = 20_000L
private const val POLL_MS = 20L

// The java of the JVM running the tests: the one program every platform of the CI has.
private val JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString()

private const val HANG_SOURCE =
    "public class Hang { public static void main(String[] a) throws Exception {" +
        " System.out.println(\"up\"); System.out.flush(); Thread.sleep(600000); } }"

class RunRegistryTest {
    private val frames = CopyOnWriteArrayList<Frame>()
    private val registry = RunRegistry { frames += it }
    private val directory = Files.createTempDirectory("kotlin-editor-run")

    @AfterTest
    fun cleanup() {
        registry.stopAll()
        directory.toFile().deleteRecursively()
    }

    private fun chunks(): List<RunChunk> =
        frames.filter { it.kind == FrameKind.Event }.map {
            it.payload.jsonObject
                .getValue("chunk")
                .runChunk()
        }

    private fun ended(): Boolean = frames.any { it.kind == FrameKind.End }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out; chunks: ${chunks()}" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun start(vararg command: String) = registry.start(RUN_ID, directory.toString(), command.toList())

    @Test
    fun `a command's output arrives as chunks, then its exit code`() {
        start(JAVA, "-version")
        await(::ended)
        val all = chunks()
        assertTrue(all.flatMap { it.lines }.any { "version" in it }, "output: ${all.flatMap { it.lines }}")
        val last = all.last()
        assertTrue(last.finished)
        assertEquals(0, last.exitCode)
        assertNull(last.error)
        assertEquals(all.map { it.seq }, (1..all.size).map { it.toLong() })
    }

    @Test
    fun `the end frame names the last chunk`() {
        start(JAVA, "-version")
        await(::ended)
        val end = frames.last { it.kind == FrameKind.End }
        assertEquals(chunks().last().seq, end.payload.jsonObject.long("seq"))
    }

    @Test
    fun `a command that fails reports its exit code`() {
        start(JAVA, "-notAnOption")
        await(::ended)
        val last = chunks().last()
        assertTrue(last.finished)
        assertNotNull(last.exitCode)
        assertTrue(last.exitCode != 0)
    }

    @Test
    fun `a program that does not exist ends the run with its reason`() {
        start("definitely-not-a-program-kotlin-editor")
        await(::ended)
        val last = chunks().last()
        assertTrue(last.finished)
        assertNull(last.exitCode)
        assertNotNull(last.error)
    }

    @Test
    fun `a missing directory ends the run with its reason`() {
        registry.start(RUN_ID, directory.resolve("gone").toString(), listOf(JAVA, "-version"))
        await(::ended)
        assertNotNull(chunks().last().error)
    }

    @Test
    fun `stopping a command that runs ends it without an exit code`() {
        directory.resolve("Hang.java").writeText(HANG_SOURCE)
        start(JAVA, "Hang.java")
        await { chunks().flatMap { it.lines }.contains("up") }
        registry.stop(RUN_ID)
        await(::ended)
        val last = chunks().last()
        assertTrue(last.finished)
        assertNull(last.exitCode)
    }

    @Test
    fun `a client that reconnects gets the chunks after its last, then the end`() {
        start(JAVA, "-version")
        await(::ended)
        val before = frames.size
        val count = chunks().size
        registry.resume(RUN_ID, lastSeq = 0)
        val replayed = frames.drop(before)
        assertEquals(count + 1, replayed.size)
        assertEquals(FrameKind.End, replayed.last().kind)
    }

    @Test
    fun `resuming after the last chunk replays only the end`() {
        start(JAVA, "-version")
        await(::ended)
        val before = frames.size
        registry.resume(RUN_ID, lastSeq = chunks().last().seq)
        assertEquals(listOf(FrameKind.End), frames.drop(before).map { it.kind })
    }

    @Test
    fun `resuming a run that is gone answers nothing`() {
        registry.resume(RUN_ID, lastSeq = 0)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `an empty command is refused`() {
        val failure = runCatching { registry.start(RUN_ID, directory.toString(), emptyList()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `finished runs are not all kept forever`() {
        repeat(10) { index ->
            registry.start(100L + index, directory.toString(), listOf(JAVA, "-version"))
            await { frames.count { it.kind == FrameKind.End && it.id == 100L + index } == 1 }
        }
        registry.start(200L, directory.toString(), listOf(JAVA, "-version"))
        assertTrue(registry.size <= 5, "runs kept: ${registry.size}")
    }
}
