package org.example.kotlineditor.state

import java.awt.Desktop
import java.io.IOException
import java.net.URI

/** Opens [url] in the default browser, off the UI thread; `xdg-open` where Java cannot (some Linux desktops). */
internal fun openInBrowser(url: String) {
    val opener =
        Thread {
            val desktop = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            try {
                if (desktop) Desktop.getDesktop().browse(URI(url)) else ProcessBuilder("xdg-open", url).start()
            } catch (expected: IOException) {
                System.err.println("Cannot open $url: ${expected.message}")
            }
        }
    opener.isDaemon = true
    opener.start()
}
