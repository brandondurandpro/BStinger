package org.example.kotlineditor.deploy.server

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SSH_CONFIG = "kotlin.editor.ssh.config"

@OptIn(ExperimentalPathApi::class)
class LocalDeployerSshHostsTest {
    private val scratch = Files.createTempDirectory("kotlin-editor-ssh-hosts")

    @AfterTest
    fun cleanup() {
        System.clearProperty(SSH_CONFIG)
        scratch.deleteRecursively()
    }

    @Test
    fun `the hosts offered are those of the SSH configuration of the machine`() {
        val config = scratch.resolve("config")
        config.writeText(
            """
            Host staging
              HostName 192.0.2.7
              User deploy
              Port 2222
            Host *.internal
              User nobody
            """.trimIndent(),
        )
        System.setProperty(SSH_CONFIG, config.toString())
        val hosts = runBlocking { LocalDeployer().sshHosts() }
        assertEquals(listOf("staging"), hosts.map { it.alias })
        assertEquals("192.0.2.7", hosts.single().hostName)
        assertEquals("deploy", hosts.single().user)
        assertEquals(2222, hosts.single().port)
    }

    @Test
    fun `no configuration offers no host`() {
        System.setProperty(SSH_CONFIG, scratch.resolve("missing").toString())
        assertEquals(emptyList(), runBlocking { LocalDeployer().sshHosts() }.map { it.alias })
    }
}
