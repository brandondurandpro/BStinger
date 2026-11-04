package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentAttachment

private const val TYPE = "type"
private const val PATH = "path"

/** The attachments of a prompt, on the wire. */
fun List<AgentAttachment>.attachmentsToJson(): JsonArray = JsonArray(map { it.toJson() })

/** The attachments of a prompt, read from the wire. */
fun JsonElement.agentAttachments(): List<AgentAttachment> = jsonArray.map { it.jsonObject.attachment() }

private fun AgentAttachment.toJson(): JsonObject =
    buildJsonObject {
        when (val attachment = this@toJson) {
            is AgentAttachment.File -> {
                put(TYPE, "file")
                put(PATH, attachment.path)
            }

            is AgentAttachment.Lines -> {
                put(TYPE, "lines")
                put(PATH, attachment.path)
                put("first", attachment.first)
                put("last", attachment.last)
                put("text", attachment.text)
            }

            is AgentAttachment.Image -> {
                put(TYPE, "image")
                put("name", attachment.name)
                put("mimeType", attachment.mimeType)
                put("data", attachment.data)
            }
        }
    }

private fun JsonObject.attachment(): AgentAttachment =
    when (val type = string(TYPE)) {
        "file" -> AgentAttachment.File(string(PATH))
        "lines" -> AgentAttachment.Lines(string(PATH), int("first"), int("last"), string("text"))
        "image" -> AgentAttachment.Image(string("name"), string("mimeType"), string("data"))
        else -> throw InvalidParamsException("Unknown attachment $type")
    }
