package org.example.kotlineditor.unit

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.RemoteTestEnvironment
import org.example.kotlineditor.e2e.SampleProject
import org.example.kotlineditor.e2e.remoteEnvironment
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.remote.RemoteException
import org.example.kotlineditor.remote.RemoteSession
import org.example.kotlineditor.remote.ServerPathPicker
import org.example.kotlineditor.remote.SshTarget
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
                        RemoteSession.connect(target(environment, project), ServerPathPicker("tester@127.0.0.1"))
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
            RemoteSession.connect(target(environment, project), ServerPathPicker("tester@127.0.0.1"))
        }

    private fun target(environment: RemoteTestEnvironment, project: SampleProject): SshTarget =
        SshTarget(
            host = "127.0.0.1",
            user = "tester",
            port = environment.ssh.port,
            identityFile = environment.ssh.identity.toString(),
            projectPath = project.path,
        )
}
