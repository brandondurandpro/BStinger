package org.example.kotlineditor.state

import java.util.Locale

private const val KILO = 1024.0

/** `1.5 KB`-style size. */
fun formatBytes(bytes: Long): String =
    when {
        bytes < KILO -> "$bytes B"
        bytes < KILO * KILO -> String.format(Locale.ROOT, "%.1f KB", bytes / KILO)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / KILO / KILO)
    }
