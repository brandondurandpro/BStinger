package org.example.kotlineditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.example.kotlineditor.model.AgentAttachment
import java.util.Base64

private val THUMBNAIL = 64.dp
private val LARGE = 720.dp

// The image of a pasted attachment, decoded once; null when its bytes are not an image.
private fun decoded(image: AgentAttachment.Image): ImageBitmap? =
    runCatching {
        val bytes = Base64.getDecoder().decode(image.data)
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

/** A pasted image in the prompt: a small preview with a button that removes it; a click shows it large. */
@Composable
internal fun ImageChip(image: AgentAttachment.Image, remove: () -> Unit) {
    val bitmap = remember(image.data) { decoded(image) }
    var large by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(THUMBNAIL)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
            .background(IdeColors.Panel)
            .testTag(AgentTags.agentAttachment(image.name)),
    ) {
        if (bitmap == null) {
            Label(image.name, Modifier.align(Alignment.Center).padding(4.dp), fontSize = 10.sp, color = IdeColors.TextSecondary)
        } else {
            Image(
                bitmap,
                image.name,
                Modifier.fillMaxSize().clickable { large = true }.testTag(AgentTags.agentImagePreview(image.name)),
                contentScale = ContentScale.Crop,
            )
        }
        Label(
            "×",
            Modifier
                .align(Alignment.TopEnd)
                .background(IdeColors.Panel, RoundedCornerShape(bottomStart = 6.dp))
                .clickable(onClick = remove)
                .padding(horizontal = 6.dp)
                .testTag(AgentTags.agentAttachmentRemove(image.name)),
            color = IdeColors.Text,
        )
    }
    if (large && bitmap != null) ImageViewer(image.name, bitmap) { large = false }
}

// The image at its size, fitted to the window; a click anywhere or Escape closes it.
@Composable
private fun ImageViewer(name: String, bitmap: ImageBitmap, close: () -> Unit) {
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .background(IdeColors.Popup, RoundedCornerShape(8.dp))
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .clickable(onClick = close)
                .testTag(AgentTags.AGENT_IMAGE_VIEWER),
        ) {
            Label(name, Modifier.padding(bottom = 8.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
            Image(bitmap, name, Modifier.size(LARGE, LARGE * bitmap.height / bitmap.width.coerceAtLeast(1)), contentScale = ContentScale.Fit)
        }
    }
}
