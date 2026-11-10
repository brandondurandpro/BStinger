package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.remote.SshConfigHost

private const val ALIAS = "alias"
private const val HOST_NAME = "hostName"
private const val USER = "user"
private const val PORT = "port"

/** The hosts of an SSH configuration: what the form offers as destinations. */
fun List<SshConfigHost>.sshHostsJson(): JsonArray =
    JsonArray(
        map { host ->
            buildJsonObject {
                put(ALIAS, host.alias)
                put(HOST_NAME, host.hostName)
                put(USER, host.user?.let(::JsonPrimitive) ?: JsonNull)
                put(PORT, host.port)
            }
        },
    )

/** Reads the hosts written by [sshHostsJson]; the key and the jump host stay on the machine that has them. */
fun JsonElement.sshHosts(): List<SshConfigHost> =
    jsonArray.map { element ->
        val fields = element.jsonObject
        SshConfigHost(
            alias = fields.string(ALIAS),
            hostName = fields.string(HOST_NAME),
            user = fields.stringOrNull(USER),
            port = fields.int(PORT),
            identityFile = null,
            proxyJump = null,
        )
    }
