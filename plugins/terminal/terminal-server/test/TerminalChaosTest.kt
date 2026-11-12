package org.example.kotlineditor.terminal.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.text
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RemoteTerminalLauncher
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.testkit.ChaosProxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Connection cuts between the client and the server while a terminal prints. */
class TerminalChaosTest {
    private val data = Files.createTempDirectory("terminal-chaos-data")
    private val project = Files.createTempDirectory("terminal-chaos-project")
    private val token = "secret"
    private val server = EditorServer(LocalServices(data.toFile()), token).start()
    private val proxy = ChaosProxy(server.port)
    private val options = ClientOptions(heartbeatMs = 200, deadAfterMs = 800, maxBackoffMs = 200)
    private val client = RpcClient("127.0.0.1", proxy.port, token, options)

    @AfterTest
    fun cleanup() {
        client.close()
        proxy.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `terminal screens stay complete and ordered across disconnections`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 120, 8, 16)
            val script =
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "for /L %i in (1,1,400) do @echo line%i\rexit\r"
                } else {
                    "stty -echo; i=1; while [ \$i -le 400 ]; do echo line\$i; i=\$((i+1)); sleep 0.002; done; exit\r"
                }
            terminal.write(script.toByteArray())
            val chaos =
                launch {
                    repeat(6) {
                        delay(120)
                        proxy.cutAll()
                    }
                }
            val screen = withTimeout(SHELL_TIMEOUT_MS) { terminal.output.first { snapshot -> !snapshot.running } }
            chaos.join()
            val lines = screen.text().mapNotNull { Regex("""line\d+$""").find(it.trim())?.value }
            assertEquals((301..400).map { "line$it" }, lines.takeLast(100))
            terminal.destroy()
        }

    private companion object {
        // The shell prints its lines and exits in 0.2 s on an idle machine, but in 4 to 9 s on Windows while the
        // other modules' tests run beside it, as in the CI, where 30 s were not always enough.
        const val SHELL_TIMEOUT_MS = 120_000L
    }
}
