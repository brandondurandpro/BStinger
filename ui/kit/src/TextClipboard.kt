package org.example.kotlineditor.ui

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * System clipboard, falling back to an in-memory one when there is no display (headless CI). It holds text, or an
 * image the prompt of the agent takes.
 */
object TextClipboard {
    /**
     * Whether the system clipboard is used. The e2e tests turn it off: they must not overwrite the user's clipboard,
     * and some headless compositors (Weston 8 and 9) crash Xwayland on the first clipboard change.
     */
    @Volatile
    var useSystem = true

    private var fallback: String? = null
    private var fallbackImage: ByteArray? = null

    fun get(): String? {
        if (!useSystem) return fallback
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: fallback
    }

    fun set(text: String) {
        fallback = text
        fallbackImage = null
        if (!useSystem) return
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    /** The image the clipboard holds, as a PNG; null when it holds text or nothing. */
    fun image(): ByteArray? {
        if (!useSystem) return fallbackImage
        return runCatching { systemImage() }.getOrNull()
    }

    /** Puts the image [png] in the in-memory clipboard, as a screenshot copied: for the tests. */
    fun setImage(png: ByteArray) {
        fallbackImage = png
        fallback = null
    }

    private fun systemImage(): ByteArray? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val holdsText = clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
        val holdsImage = clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
        val image = if (holdsImage && !holdsText) clipboard.getData(DataFlavor.imageFlavor) as? Image else null
        return image?.let(::pngOf)
    }

    // [image] as a PNG; null for an empty one.
    private fun pngOf(image: Image): ByteArray? {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val drawn = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = drawn.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        val png = ByteArrayOutputStream()
        ImageIO.write(drawn, "png", png)
        return png.toByteArray()
    }
}
