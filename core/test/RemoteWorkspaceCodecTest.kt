package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.state.RemoteWorkspace
import org.example.kotlineditor.state.RemoteWorkspaceCodec
import org.example.kotlineditor.state.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteWorkspaceCodecTest {
    @Test
    fun `workspaces survive a preferences round trip, including odd paths`() {
        val entries =
            listOf(
                RemoteWorkspace(
                    SshTarget(host = "prod", alias = "prod", user = "me", port = 2222),
                    """/srv/a${'\t'}b${'\n'}c\d""",
                    20,
                ),
                RemoteWorkspace(
                    SshTarget(host = "example.com", user = "me", port = 22, identityFile = "/keys/id"),
                    "/home/me/project",
                    10,
                ),
            )

        val decoded = RemoteWorkspaceCodec.decode(RemoteWorkspaceCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `invalid lines are skipped and the newest entries come first`() {
        val older =
            RemoteWorkspace(SshTarget(host = "a"), "/a", 1)
        val newer =
            RemoteWorkspace(SshTarget(host = "b"), "/b", 2)
        val text =
            listOf(RemoteWorkspaceCodec.encode(listOf(older, newer)), "not a workspace", "")
                .joinToString("\n")

        assertEquals(listOf(newer, older), RemoteWorkspaceCodec.decode(text))
        assertTrue(RemoteWorkspaceCodec.decode("").isEmpty())
    }

    @Test
    fun `an alias matches only the same alias and path`() {
        val workspace = RemoteWorkspace(SshTarget(host = "prod", alias = "prod"), "/srv/app", 1)

        assertTrue(workspace.matches(SshTarget(host = "prod", alias = "prod", user = "other"), "/srv/app"))
        assertFalse(workspace.matches(SshTarget(host = "prod"), "/srv/app"))
        assertFalse(workspace.matches(SshTarget(host = "prod", alias = "prod"), "/srv/other"))
    }
}
