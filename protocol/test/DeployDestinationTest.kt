package org.example.kotlineditor.model

import org.example.kotlineditor.rpc.deployDestination
import org.example.kotlineditor.rpc.deployResult
import org.example.kotlineditor.rpc.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the user types as the place of a deployment, and how it travels. */
class DeployDestinationTest {
    @Test
    fun `a path is a folder of the server`() {
        for (text in listOf("/var/www", "./out", "dist", "../site", "C:\\site", "C:/site", "/srv/a:b")) {
            val destination = DeployDestination.parse(text)
            assertEquals(DeployDestination(text), destination, text)
            assertFalse(destination?.isRemote ?: true, text)
        }
    }

    @Test
    fun `a host and a path are a folder of another machine`() {
        val full = DeployDestination.parse("web@example.com:/var/www")
        assertEquals(DeployDestination("/var/www", "web@example.com"), full)
        assertTrue(full?.isRemote ?: false)
        assertEquals("web@example.com:/var/www", full.toString())
        // An alias of ~/.ssh/config, a path relative to the home of the SSH user, and no path at all (the home).
        assertEquals(DeployDestination("site", "staging"), DeployDestination.parse("staging:site"))
        assertEquals(DeployDestination("~/site", "staging"), DeployDestination.parse("staging:~/site"))
        assertEquals(DeployDestination(".", "staging"), DeployDestination.parse("staging:"))
    }

    @Test
    fun `surrounding spaces are ignored and nothing is no destination`() {
        assertEquals(DeployDestination("/srv/app"), DeployDestination.parse("  /srv/app \n"))
        assertNull(DeployDestination.parse(""))
        assertNull(DeployDestination.parse("   "))
    }

    @Test
    fun `a host that ssh would take for an option or a command is refused`() {
        for (text in listOf("-oProxyCommand=evil:/x", "a b:/x", "host;rm:/x", "\$(id):/x", "`id`:/x")) {
            assertNull(DeployDestination.parse(text), text)
        }
    }

    @Test
    fun `a destination and a result come back as they were sent`() {
        val remote = DeployDestination("/var/www", "web@example.com")
        assertEquals(remote, remote.toJson().deployDestination())
        val local = DeployDestination("/srv/app")
        assertEquals(local, local.toJson().deployDestination())
        val result = DeployResult(12, 3_000_000_000L)
        assertEquals(result, result.toJson().deployResult())
    }
}
