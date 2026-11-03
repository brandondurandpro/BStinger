package org.example.kotlineditor.acp

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentRegistryEntry
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val HTTP_OK = 200
private const val MAX_REGISTRY_SIZE = 8 * 1024 * 1024
private val TIMEOUT: Duration = Duration.ofSeconds(20)

/** The ACP registry of agents, read once an hour at most, and the agents it lists installed on demand. */
internal class AgentRegistry(
    private val url: String,
    private val installer: AgentInstaller,
    private val cacheMs: Long,
    private val fetch: (String) -> InputStream = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: List<RegistryAgent>? = null
    private var readAt = 0L

    /** The agents of the registry, and whether this machine can run each one. Blocking. */
    fun entries(): List<AgentRegistryEntry> =
        agents().map { agent ->
            AgentRegistryEntry(agent.id, agent.name, agent.version, agent.description, installer.unavailable(agent))
        }

    /** The command that starts the agent [id], installed first when needed. Blocking. */
    fun command(id: String): AgentCommand {
        val agent = agents().firstOrNull { it.id == id } ?: throw IOException("No agent $id in the registry")
        return installer.command(agent)
    }

    @Synchronized
    private fun agents(): List<RegistryAgent> {
        val known = cached
        if (known != null && clock() - readAt < cacheMs) return known
        val text =
            try {
                fetch(url).use { String(it.readNBytes(MAX_REGISTRY_SIZE), Charsets.UTF_8) }
            } catch (failure: IOException) {
                throw IOException("Cannot read the agent registry $url: ${failure.message}", failure)
            }
        return parseRegistry(text).also {
            cached = it
            readAt = clock()
        }
    }
}

private fun httpGet(url: String): InputStream {
    val client =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build()
    val request =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .GET()
            .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != HTTP_OK) {
        response.body().close()
        throw IOException("HTTP ${response.statusCode()}")
    }
    return response.body()
}
