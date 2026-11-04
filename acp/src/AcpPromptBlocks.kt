package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

// The content blocks of ACP that a prompt sends: text, an image, a file with its text, a link to a file.

private const val TYPE = "type"
private const val TEXT = "text"
private const val URI = "uri"
private const val MIME_TYPE = "mimeType"

internal fun textBlock(text: String): JsonObject =
    buildJsonObject {
        put(TYPE, "text")
        put(TEXT, text)
    }

internal fun image(mimeType: String, data: String, uri: String?): JsonObject =
    buildJsonObject {
        put(TYPE, "image")
        put(MIME_TYPE, mimeType)
        put("data", data)
        if (uri != null) put(URI, uri)
    }

internal fun resource(uri: String, text: String): JsonObject =
    buildJsonObject {
        put(TYPE, "resource")
        putJsonObject("resource") {
            put(URI, uri)
            put(MIME_TYPE, "text/plain")
            put(TEXT, text)
        }
    }

internal fun link(file: Path, size: Long): JsonObject =
    buildJsonObject {
        put(TYPE, "resource_link")
        put(URI, file.toUri().toString())
        put("name", file.fileName.toString())
        put("size", size)
    }

// The text of [bytes] when they are UTF-8 text; null for a binary file.
internal fun textOf(bytes: ByteArray): String? {
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    // A binary file does not decode.
    val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    return text?.takeIf { '\u0000' !in it }
}
