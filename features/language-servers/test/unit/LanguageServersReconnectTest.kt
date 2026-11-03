package org.example.kotlineditor.state

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.LanguageServers
import kotlin.test.Test

private const val TIMEOUT_MS = 5_000L

class LanguageServersReconnectTest {
    private class CountingServers : LanguageServers by LanguageServers.None {
        val declared = MutableStateFlow(0)

        override suspend fun use(project: String, files: List<String>): List<String> {
            declared.update { it + 1 }
            return emptyList()
        }
    }

    @Test
    fun `the open files are declared again after a reconnection too quick for the collectors to see`() =
        runBlocking {
            val servers = CountingServers()
            val connections = MutableStateFlow(1L)
            val jobs = Job(coroutineContext[Job])
            val state = LanguageServersState(servers, this + jobs, connections)
            state.declare("/project", listOf("src/index.php"))
            withTimeout(TIMEOUT_MS) { servers.declared.first { it == 1 } }
            // Lost and back twice before the collectors ran (the status, back to Connected, showed nothing): the
            // restarted server forgot the declaration all the same.
            connections.value = 2
            connections.value = 3
            withTimeout(TIMEOUT_MS) { servers.declared.first { it == 2 } }
            jobs.cancel()
        }
}
