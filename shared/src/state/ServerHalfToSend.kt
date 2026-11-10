package org.example.kotlineditor.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.RpcClient
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** One plugin whose server half is on the disk of the client, to be present on the server. */
internal class ServerHalfToSend(val id: String, val manifest: String, val jar: File)

/**
 * Makes the server hold the server half of every plugin that has one, in the version the client has. A half the server
 * already has (same SHA-256) is not sent again; one it lacks or has in another version is installed. Returns the
 * reason for each plugin the server could not take.
 */
internal suspend fun syncServerHalves(client: RpcClient, halves: List<ServerHalfToSend>): Map<String, String> {
    val failures = linkedMapOf<String, String>()
    val installed = installedOnServer(client)
    for (half in halves) {
        val digest = sha256Of(half.jar.readBytes())
        if (installed[half.id] == digest) continue
        try {
            val encoded = Base64.getEncoder().encodeToString(half.jar.readBytes())
            client.call(Method.PluginInstall) {
                put("manifest", half.manifest)
                put("jar", encoded)
                put("sha256", digest)
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            failures[half.id] = failure.message ?: failure.javaClass.simpleName
        }
    }
    return failures
}

private suspend fun installedOnServer(client: RpcClient): Map<String, String> {
    val listed: JsonArray = client.call(Method.PluginList).jsonArray
    return listed.associate { entry ->
        val fields = entry.jsonObject
        fields.getValue("id").jsonPrimitive.content to fields.getValue("sha256").jsonPrimitive.content
    }
}

private fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
