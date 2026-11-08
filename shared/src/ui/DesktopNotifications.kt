package org.example.kotlineditor.ui

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

private const val ICON_SIZE = 16

/** A message of the desktop (the system tray), for what happens while the window is in the background. */
internal object DesktopNotifications {
    private val icon: TrayIcon? by lazy { trayIcon() }

    /** Shows [text] under [title], when the desktop has a system tray; otherwise nothing. */
    fun show(title: String, text: String, error: Boolean) {
        val type = if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO
        icon?.displayMessage(title, text, type)
    }

    private fun trayIcon(): TrayIcon? {
        val supported = runCatching { SystemTray.isSupported() }.getOrDefault(false)
        if (!supported) return null
        val image = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val tray = TrayIcon(image, "Kotlin Editor")
        tray.isImageAutoSize = true
        val added = runCatching { SystemTray.getSystemTray().add(tray) }.isSuccess
        return tray.takeIf { added }
    }
}
