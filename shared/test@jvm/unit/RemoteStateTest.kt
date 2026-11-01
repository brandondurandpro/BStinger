package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.state.RemoteDialogState
import org.example.kotlineditor.state.RemotePreferenceKeys
import org.example.kotlineditor.state.toSshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
