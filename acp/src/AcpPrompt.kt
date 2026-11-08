package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentAttachment
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

// The content blocks of a prompt (ACP `ContentBlock`): the text typed, then what the user joined to it. Every agent
// takes text and links to files (`resource_link`); a file goes whole (`resource`) to an agent that takes embedded
// context, an image (`image`) to one that takes images.

// Larger files go as links: the agent reads what it needs of them.
private const val MAX_EMBEDDED_BYTES = 256 * 1024

// Larger images go as links.
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

private val IMAGE_TYPES =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
    )

/** What a prompt sends: its [blocks], and why some attachments were left out, one sentence each. */
internal class PromptContent(val blocks: List<JsonObject>, val leftOut: List<String>)

/**
 * The blocks of [text] and [attachments], whose paths are relative to [directory], as an agent that can [take]
 * receives them. Reads the files: call it on an IO thread.
 */
internal fun promptContent(
    text: String,
    attachments: List<AgentAttachment>,
    directory: String,
    take: PromptCapabilities,
): PromptContent {
    val blocks = mutableListOf(textBlock(text))
    val leftOut = mutableListOf<String>()
    val root = Path.of(directory).toAbsolutePath().normalize()
    for (attachment in attachments) {
        val block =
            try {
                attachment.block(root, take)
            } catch (failure: IOException) {
                leftOut += "${attachment.label} was not sent: ${failure.message}."
                null
            }
        block?.let(blocks::add)
    }
    return PromptContent(blocks, leftOut)
}

private fun AgentAttachment.block(root: Path, take: PromptCapabilities): JsonObject =
    when (this) {
        is AgentAttachment.File -> fileBlock(fileUnder(root, path), take)
        is AgentAttachment.Lines -> linesBlock(this, fileUnder(root, path), take)
        is AgentAttachment.Image -> imageBlock(this, take)
        is AgentAttachment.Audio -> audioBlock(this, take)
    }

// The file at [path] under [root]; fails for one outside it.
private fun fileUnder(root: Path, path: String): Path {
    val file = root.resolve(path).normalize()
    if (!file.startsWith(root)) throw IOException("it is outside the directory of the agent")
    return file
}

private fun fileBlock(file: Path, take: PromptCapabilities): JsonObject {
    if (!Files.isRegularFile(file)) throw IOException("the file no longer exists")
    val size = Files.size(file)
    val name = file.fileName.toString()
    val extension = name.substringAfterLast('.').lowercase()
    val imageType = IMAGE_TYPES[extension]
    val sendsImage = imageType != null && take.image && size <= MAX_IMAGE_BYTES
    if (sendsImage) {
        val data = Base64.getEncoder().encodeToString(Files.readAllBytes(file))
        return image(checkNotNull(imageType), data, file.toUri().toString())
    }
    val embeds = imageType == null && take.embedded && size <= MAX_EMBEDDED_BYTES
    val text = if (embeds) textOf(Files.readAllBytes(file)) else null
    return if (text != null) resource(file.toUri().toString(), text) else link(file, size)
}

private fun linesBlock(lines: AgentAttachment.Lines, file: Path, take: PromptCapabilities): JsonObject {
    if (!take.embedded) {
        val quoted = listOf("${lines.label} (${lines.path}):", "```", lines.text, "```")
        return textBlock(quoted.joinToString("\n"))
    }
    val uri = "${file.toUri()}#L${lines.first}-L${lines.last}"
    return resource(uri, lines.text)
}

private fun imageBlock(pasted: AgentAttachment.Image, take: PromptCapabilities): JsonObject {
    if (!take.image) throw IOException("the agent takes no images")
    return image(pasted.mimeType, pasted.data, uri = null)
}

private fun audioBlock(sound: AgentAttachment.Audio, take: PromptCapabilities): JsonObject {
    if (!take.audio) throw IOException("the agent takes no audio")
    return buildJsonObject {
        put("type", "audio")
        put("mimeType", sound.mimeType)
        put("data", sound.data)
    }
}

/**
 * What the prompt shows of this block of a prompt the agent replays, when the user joined it: the name of a file, of
 * lines of one (`Main.kt:3-7`), of an image; null for text.
 */
internal fun JsonObject.contextLabel(): String? {
    val resource = get("resource").obj()
    return when (text("type")) {
        "resource_link" -> text("name") ?: text("uri")?.let(::labelOf)
        "resource" -> resource?.text("uri")?.let(::labelOf)
        "image" -> text("uri")?.let(::labelOf) ?: "Image"
        "audio" -> "Audio"
        else -> null
    }
}

// `file:///p/Main.kt#L3-L7` → `Main.kt:3-7`; `file:///p/Main.kt` → `Main.kt`.
private fun labelOf(uri: String): String {
    val base = uri.substringBefore('#')
    val path = runCatching { URI(base).path }.getOrNull() ?: base
    val name = path.trimEnd('/').substringAfterLast('/')
    val fragment = uri.substringAfter('#', "")
    val lines = Regex("""L(\d+)(?:-L?(\d+))?""").matchEntire(fragment) ?: return name
    val first = lines.groupValues[1]
    val last = lines.groupValues[2].takeIf { it.isNotEmpty() && it != first }
    return if (last == null) "$name:$first" else "$name:$first-$last"
}
