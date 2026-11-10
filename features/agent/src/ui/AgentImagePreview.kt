package org.example.kotlineditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.window.DialogProperties
import org.example.kotlineditor.model.AgentAttachment
import java.util.Base64

private val THUMBNAIL = 64.dp
// The share of the window the viewer takes, in width and in height.
private const val VIEWER_SHARE = 0.9f

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
            .clip(IdeShapes.Large)
            .border(1.dp, IdeColors.Separator, IdeShapes.Large)
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
        CloseIcon(
            Modifier
                .align(Alignment.TopEnd)
                .background(IdeColors.Panel, RoundedCornerShape(bottomStart = 6.dp))
                .clickable(onClick = remove)
                .padding(3.dp)
                .size(14.dp)
                .testTag(AgentTags.agentAttachmentRemove(image.name)),
            color = IdeColors.Text,
        )
    }
    if (large && bitmap != null) ImageViewer(image.name, bitmap) { large = false }
}

// The image large, fitted to most of the window; the Close button at the top right or Escape closes it; a click on the image does nothing.
@Composable
private fun ImageViewer(name: String, bitmap: ImageBitmap, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(VIEWER_SHARE)
                .fillMaxHeight(VIEWER_SHARE)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(12.dp)
                .testTag(AgentTags.AGENT_IMAGE_VIEWER),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(name, Modifier.weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp)
                IdeButton("Close", close, Modifier.testTag(AgentTags.AGENT_IMAGE_CLOSE))
            }
            Image(
                bitmap,
                name,
                Modifier.weight(1f).fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
