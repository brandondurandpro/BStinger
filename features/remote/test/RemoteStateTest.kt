package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.RemoteServerInUseException
import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.state.RemoteDialogState
import org.example.kotlineditor.state.RemotePreferenceKeys
import org.example.kotlineditor.state.failedWith
import org.example.kotlineditor.state.toSshTarget
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteStateTest {
    @Test
    fun `the dialog trims its fields and refuses an empty host or a bad port`() {
        val target =
            RemoteDialogState(
                host = "  example.com ",
                user = " me ",
                port = " 2222 ",
                identityFile = " /keys/id ",
                projectPath = " /src ",
            ).toSshTarget().getOrThrow()

        assertEquals("example.com", target.host)
        assertEquals("me", target.user)
        assertEquals(2222, target.port)
        assertEquals("/keys/id", target.identityFile)
        assertEquals("/src", target.projectPath)

        assertTrue(RemoteDialogState(host = " ").toSshTarget().isFailure)
        assertTrue(RemoteDialogState(host = "host", port = "0").toSshTarget().isFailure)
        assertTrue(RemoteDialogState(host = "host", port = "70000").toSshTarget().isFailure)
        assertTrue(RemoteDialogState(host = "host", port = "abc").toSshTarget().isFailure)
        // ssh options must not be smuggled through the host or user fields.
        assertTrue(RemoteDialogState(host = "host", user = "-oProxyCommand=bad").toSshTarget().isFailure)
        assertTrue(RemoteDialogState(host = "-oProxyCommand=bad").toSshTarget().isFailure)
        assertTrue(RemoteDialogState(host = "two words").toSshTarget().isFailure)
    }

    @Test
    fun `an ssh config alias is kept in the dialog and in the preferences`() {
        val target =
            RemoteDialogState(host = "prod", alias = "prod", port = "2222")
                .toSshTarget()
                .getOrThrow()

        assertEquals("prod", target.alias)
        assertEquals("prod", target.destination)
        assertTrue(target.usesSshConfig)
        assertEquals(target, RemotePreferenceKeys.read(RemotePreferenceKeys.write(target)))
    }

    @Test
    fun `the last connection survives a preferences round trip`() {
        val target =
            SshTarget(
                host = "example.com",
                user = "me",
                port = 2222,
                identityFile = "/keys/id",
                projectPath = "/src",
            )
        assertEquals(target, RemotePreferenceKeys.read(RemotePreferenceKeys.write(target)))

        val withoutOptionalFields =
            RemotePreferenceKeys.read(
                mapOf(
                    RemotePreferenceKeys.HOST to "host",
                    RemotePreferenceKeys.PORT to "not a port",
                ),
            )
        assertEquals(SshTarget(host = "host", port = 22), withoutOptionalFields)
        assertNull(RemotePreferenceKeys.read(emptyMap()))
    }

    @Test
    fun `the in-use line of start sh is read into the question`() {
        val inUse = RemoteServerInUseException.fromOutput("noise\nin-use sessions=3 version=2025.9 protocol=10\n")
        assertEquals(3, inUse.sessions)
        assertEquals("2025.9", inUse.version)
        assertEquals(10, inUse.protocol)
        assertTrue("still has 3 sessions" in inUse.message.orEmpty(), inUse.message)

        val unknown = RemoteServerInUseException.fromOutput("in-use sessions=unknown version= protocol=10")
        assertNull(unknown.sessions)
        assertTrue("may still have sessions" in unknown.message.orEmpty(), unknown.message)
    }

    @Test
    fun `a server in use is a question, and an agreement is not kept after a failure`() {
        val dialog = RemoteDialogState(host = "h", connecting = true, replaceServer = true)
        val inUse = RemoteServerInUseException(1, "2025.9", 10)

        val asked = dialog.failedWith(inUse, "h")
        assertEquals(inUse.message, asked.replaceQuestion)
        assertNull(asked.error)
        assertFalse(asked.replaceServer)
        assertFalse(asked.connecting)

        val failed = dialog.failedWith(IOException("refused"), "h")
        assertEquals("refused", failed.error)
        assertNull(failed.replaceQuestion)
        assertFalse(failed.replaceServer)
    }
}
