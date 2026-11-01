package org.example.kotlineditor.unit

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.RemoteTestEnvironment
import org.example.kotlineditor.e2e.SampleProject
import org.example.kotlineditor.e2e.remoteEnvironment
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.remote.RemoteBundles
import org.example.kotlineditor.remote.RemoteException
import org.example.kotlineditor.remote.RemoteSession
import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.remote.explainSshError
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The SSH transport without the UI: install, daemon, tunnel, protocol. */
class RemoteTransportTest {
    @Test
    fun `installs the server, starts its daemon and reads a file through the tunnel`() {
        if (!RemoteTestEnvironment.available) return
        val environment = assertNotNull(remoteEnvironment())
        val project =
            SampleProject.standard().apply {
                file("remote-hello.txt", "from the remote\n")
            }
        try {
            val session = connect(environment, project)
            try {
                val root = runBlocking { session.services.fileSystem.resolveDirectory(project.path) }
                assertNotNull(root)
                val file = runBlocking { session.services.fileSystem.fileAt("${project.path}/remote-hello.txt") }
                assertNotNull(file)
                val content = runBlocking { session.services.fileSystem.read(file) }
                assertEquals(listOf("from the remote", ""), (content as FileContent.Text).lines)
            } finally {
                session.close()
            }
        } finally {
            project.delete()
        }
    }

    @Test
    fun `a daemon restarted after a crash keeps the token the client already has`() {
        if (!RemoteTestEnvironment.available) return
        val environment = assertNotNull(remoteEnvironment())
        val project = SampleProject.standard()
        try {
            val first = connect(environment, project)
            val token = first.server.token
            first.close()
            RemoteTestEnvironment.stopDaemon()

            val second = connect(environment, project)
            try {
                assertEquals(token, second.server.token, "the token must survive a daemon restart")
            } finally {
                second.close()
            }
        } finally {
            project.delete()
        }
    }

    @Test
    fun `a host key that is not in known hosts is refused`() {
        if (!RemoteTestEnvironment.available) return
        val environment = assertNotNull(remoteEnvironment())
        val project = SampleProject.standard()
        val previous = System.getProperty("kotlin.editor.ssh.knownHosts")
        val emptyKnownHosts = Files.createTempFile("empty-known-hosts", ".txt")
        System.setProperty("kotlin.editor.ssh.knownHosts", emptyKnownHosts.toString())
        try {
            val failure =
                assertFailsWith<RemoteException> {
                    runBlocking {
                        RemoteSession.connect(target(environment, project))
                    }
                }
            val message = failure.message.orEmpty()
            val cause = failure.cause?.message.orEmpty()
            assertTrue(
                message.contains("Host key", ignoreCase = true) || cause.contains("Host key", ignoreCase = true),
                "unexpected error: ${failure.message}",
            )
        } finally {
            if (previous != null) {
                System.setProperty("kotlin.editor.ssh.knownHosts", previous)
            } else {
                System.clearProperty("kotlin.editor.ssh.knownHosts")
            }
            emptyKnownHosts.toFile().delete()
            project.delete()
        }
    }

    private fun connect(environment: RemoteTestEnvironment, project: SampleProject): RemoteSession =
        runBlocking {
            RemoteSession.connect(target(environment, project))
        }

    private fun target(environment: RemoteTestEnvironment, project: SampleProject): SshTarget =
        SshTarget(
            host = "127.0.0.1",
            user = "tester",
            port = environment.ssh.port,
            identityFile = environment.ssh.identity.toString(),
            projectPath = project.path,
        )

    @Test
    fun `ssh errors say how to trust a host that is not known yet`() {
        val unknown = explainSshError("Host key verification failed.")
        assertTrue("known_hosts" in unknown, unknown)
        assertTrue(unknown.startsWith("Host key verification failed."), "ssh's own words come first")

        val changed = "@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@\nHost key verification failed."
        assertEquals(changed, explainSshError(changed), "a changed key is a warning, not a step to follow")
        assertEquals("Permission denied (publickey).", explainSshError("Permission denied (publickey)."))
    }

    @Test
    fun `a bundle is identified by its bytes, not by its version number`() {
        val first = Files.createTempFile("bundle", ".tar.gz")
        val second = Files.createTempFile("bundle", ".tar.gz")
        try {
            Files.writeString(first, "one")
            Files.writeString(second, "two")
            val id = RemoteBundles.buildId(first.toFile())
            assertEquals(16, id.length)
            assertEquals(id, RemoteBundles.buildId(first.toFile()))
            assertTrue(id != RemoteBundles.buildId(second.toFile()))
        } finally {
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
        }
    }
}
