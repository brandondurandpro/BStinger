package org.example.kotlineditor.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** System clipboard, falling back to an in-memory one when there is no display (headless CI). */
object TextClipboard {
    /**
     * Whether the system clipboard is used. The e2e tests turn it off: they must not overwrite the user's clipboard,
     * and some headless compositors (Weston 8 and 9) crash Xwayland on the first clipboard change.
     */
    @Volatile
    var useSystem = true

    private var fallback: String? = null

    fun get(): String? {
        if (!useSystem) return fallback
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: fallback
    }

    fun set(text: String) {
        fallback = text
        if (!useSystem) return
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
