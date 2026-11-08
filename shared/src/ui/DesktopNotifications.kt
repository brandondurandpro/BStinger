package org.example.kotlineditor.ui

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File

private const val ICON_SIZE = 16
private const val APP_NAME = "Kotlin Editor"

/**
 * A message of the desktop, for what happens while the window is in the background. On Linux it goes through
 * `notify-send` (the notification daemon of Cinnamon, GNOME, KDE…), which works where Java's system tray does not;
 * elsewhere through the system tray.
 */
internal object DesktopNotifications {
    private val notifySend: String? by lazy { onPath("notify-send") }
    private val icon: TrayIcon? by lazy { trayIcon() }

    /** Shows [text] under [title]; nothing when the desktop offers no way to. */
    fun show(title: String, text: String, error: Boolean) {
        val command = notifySend
        if (command != null) {
            sendWithNotifySend(command, title, text, error)
            return
        }
        val type = if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO
        icon?.displayMessage(title, text, type)
    }

    private fun sendWithNotifySend(command: String, title: String, text: String, error: Boolean) {
        val urgency = if (error) "critical" else "normal"
        val arguments = listOf(command, "--app-name=$APP_NAME", "--urgency=$urgency", "--", title, text)
        val builder = ProcessBuilder(arguments).redirectErrorStream(true)
        // The libraries of a packaged IDE (an AppImage…) must not replace those `notify-send` was built with.
        builder.environment().remove("LD_LIBRARY_PATH")
        builder.environment().remove("LD_PRELOAD")
        runCatching { builder.start() }
    }

    private fun onPath(program: String): String? {
        val system = System.getProperty("os.name").orEmpty()
        if (!system.contains("linux", ignoreCase = true)) return null
        val directories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        val found = directories.map { File(it, program) }.firstOrNull { it.canExecute() }
        return found?.path
    }

    private fun trayIcon(): TrayIcon? {
        val supported = runCatching { SystemTray.isSupported() }.getOrDefault(false)
        if (!supported) return null
        val image = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val tray = TrayIcon(image, APP_NAME)
        tray.isImageAutoSize = true
        val added = runCatching { SystemTray.getSystemTray().add(tray) }.isSuccess
        return tray.takeIf { added }
    }
}
