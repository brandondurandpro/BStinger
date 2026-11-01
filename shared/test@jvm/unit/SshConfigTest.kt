package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.SshConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SshConfigTest {
    private val dir: Path = Files.createTempDirectory("ssh-config")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `reads concrete aliases from the config and its includes`() {
        dir.resolve("extra").writeText(
            """
            Host gamma
              HostName 192.0.2.3
            """.trimIndent(),
        )
        val config =
            dir.resolve("config").apply {
                writeText(
                    """
                    Host alpha
                      HostName 192.0.2.1
                      User alice
                      Port 2222
                    Host *.wild
                      HostName 192.0.2.9
                    Include extra
                    Host beta
                      HostName 192.0.2.2
                    """.trimIndent(),
                )
            }

        val hosts = SshConfig.load(config.toFile())

        assertEquals(setOf("alpha", "beta", "gamma"), hosts.map { it.alias }.toSet())
        val alpha = assertNotNull(hosts.firstOrNull { it.alias == "alpha" })
        assertEquals("192.0.2.1", alpha.hostName)
        assertEquals("alice", alpha.user)
        assertEquals(2222, alpha.port)
        assertEquals("alice@192.0.2.1:2222", alpha.summary)
        assertNull(hosts.firstOrNull { it.alias == "*.wild" })
    }

    @Test
    fun `a missing config is empty rather than an error`() {
        assertEquals(emptyList(), SshConfig.load(dir.resolve("missing").toFile()))
    }
}
