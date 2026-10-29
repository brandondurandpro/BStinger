package org.example.kotlineditor.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** System clipboard, falling back to an in-memory one when there is no display (headless CI). */
object TextClipboard {
    private var fallback: String? = null

    fun get(): String? =
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: fallback

    fun set(text: String) {
        fallback = text
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
