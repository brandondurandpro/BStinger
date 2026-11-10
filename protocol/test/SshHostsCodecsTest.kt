package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.rpc.sshHosts
import org.example.kotlineditor.rpc.sshHostsJson
import kotlin.test.Test
import kotlin.test.assertEquals

class SshHostsCodecsTest {
    @Test
    fun `hosts survive the round trip, without the key and the jump host`() {
        val hosts =
            listOf(
                SshConfigHost("staging", "192.0.2.7", "deploy", 2222, "/home/me/.ssh/id", "bastion"),
                SshConfigHost("prod", "example.com", null, 22, null, null),
            )
        val read = hosts.sshHostsJson().sshHosts()
        assertEquals(hosts.map { it.copy(identityFile = null, proxyJump = null) }, read)
    }

    @Test
    fun `no host is an empty list`() {
        assertEquals(emptyList(), emptyList<SshConfigHost>().sshHostsJson().sshHosts())
    }
}
