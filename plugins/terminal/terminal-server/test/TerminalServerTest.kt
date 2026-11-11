package org.example.kotlineditor.terminal.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.model.text
import org.example.kotlineditor.rpc.RemoteTerminalLauncher
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COMMANDS = 50
private const val ORDER_FILE = "order.txt"

/** The terminal feature through a real server and a real socket: the plugin is found on the class path. */
class TerminalServerTest {
    private val data = Files.createTempDirectory("terminal-data")
    private val project = Files.createTempDirectory("terminal-project")
    private val server = EditorServer(LocalServices(data.toFile())).start()
    private val client = RpcClient("127.0.0.1", server.port, server.token)

    @AfterTest
    fun cleanup() {
        client.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `a remote terminal streams its screen`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 24, 8, 16)
            terminal.write("echo hello\r".toByteArray())
            val screen =
                withTimeout(10_000) {
                    terminal.output.first { snapshot -> "hello" in printed(snapshot) }
                }
            assertTrue("hello" in printed(screen))
            terminal.destroy()
        }

    @Test
    fun `commands reach the terminal in order`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 24, 8, 16)
            // Each command appends its number to a file, read once the shell has exited. The screen cannot tell the
            // order: commands typed ahead are echoed while an earlier one runs, and that echo, the prompts and the
            // outputs share rows, an output being sometimes overwritten (`echo n45` / `n46` / `# n46` on CI).
            repeat(COMMANDS) { terminal.write("echo n$it >> $ORDER_FILE\r".toByteArray()) }
            terminal.write("exit\r".toByteArray())
            withTimeout(20_000) { terminal.output.first { snapshot -> !snapshot.running } }
            // cmd.exe keeps the space before `>>`.
            val lines = project.resolve(ORDER_FILE).readLines()
            val executed = lines.map(String::trim).filter(String::isNotEmpty)
            assertEquals((0 until COMMANDS).map { "n$it" }, executed)
            terminal.destroy()
        }

    // What the commands printed, without the shell's prompt. Commands are written before the shell is ready, so the
    // terminal echoes them early and the output lands after a prompt (`$ hello`, `C:\project>hello`); lines echoing
    // a command are skipped. Commands end with `\r`, the Enter key: a Unix PTY turns it into `\n`, ConPTY needs it.
    private fun printed(snapshot: TerminalSnapshot): List<String> =
        snapshot
            .text()
            .filter { "echo" !in it }
            .mapNotNull { Regex("""([\w-]+)\s*$""").find(it)?.groupValues?.get(1) }
}
