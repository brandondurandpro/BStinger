package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.ServerMemory
import org.example.kotlineditor.state.IdeCpu
import org.example.kotlineditor.state.IdeMemory
import org.example.kotlineditor.state.LanguageServersHost
import org.example.kotlineditor.state.LanguageServersState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.cpuText
import org.example.kotlineditor.state.memoryText

private const val ONE_PROCESS = "UI + Server"

/**
 * The memory of the IDE in the status bar: the process of the UI, the process of the IDE server, and both. When the
 * server runs in the process of the UI there is one number, which is both.
 */
@Composable
internal fun IdeMemoryStatusItems(project: LanguageServersHost, state: LanguageServersState) {
    val server = state.status.server
    // Nothing before the first status: the server's part is not known yet.
    if (server.heapMaxBytes <= 0) return
    val memory = state.memory
    val tooltip = memoryTooltip(memory, server)
    for (item in statusItems(memory, server)) {
        StatusItem(
            text = item.text,
            color = IdeColors.TextSecondary,
            tooltip = tooltip,
            tag = item.tag,
            tooltipTag = LanguageServerTags.STATUS_MEMORY_TOOLTIP,
        ) { project.showToolWindow(ToolWindow.LanguageServers) }
    }
}

/**
 * The processor use of the IDE in the status bar, beside its memory: the UI and the IDE server, or one number when the
 * server runs in the process of the UI. Nothing while neither is measured (a first sample, an OS that does not tell).
 */
@Composable
internal fun IdeCpuStatusItem(project: LanguageServersHost, state: LanguageServersState) {
    // Nothing before the first status, like the memory.
    if (state.status.server.heapMaxBytes <= 0) return
    val cpu = state.cpu
    val text = cpuStatusText(cpu) ?: return
    StatusItem(
        text = text,
        color = IdeColors.TextSecondary,
        tooltip = cpuTooltip(cpu),
        tag = LanguageServerTags.STATUS_CPU,
        tooltipTag = LanguageServerTags.STATUS_CPU_TOOLTIP,
    ) { project.showToolWindow(ToolWindow.LanguageServers) }
}

/** The same numbers in the Language Servers window, with the heap of the server's JVM. */
@Composable
internal fun IdeMemorySection(state: LanguageServersState) {
    val server = state.status.server
    val memory = state.memory
    Column(
        SECTION.testTag(LanguageServerTags.LANGUAGE_SERVERS_IDE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (memory.sameProcess) {
            MemoryLine("UI and IDE server (one process)", textOf(memory.totalBytes), LanguageServerTags.MEMORY_TOTAL)
        } else {
            MemoryLine("UI process", textOf(memory.uiBytes), LanguageServerTags.MEMORY_UI)
            MemoryLine("IDE server process", textOf(memory.serverBytes), LanguageServerTags.MEMORY_SERVER)
            MemoryLine("Total", textOf(memory.totalBytes), LanguageServerTags.MEMORY_TOTAL)
        }
        CpuLines(state.cpu)
        if (server.heapMaxBytes > 0) {
            MemoryLine("IDE server · heap", "${memoryText(server.heapUsedBytes)} / ${memoryText(server.heapMaxBytes)}")
            ProgressBar(server.heapUsedBytes.toFloat() / server.heapMaxBytes, Modifier.fillMaxWidth())
        }
    }
}

/** One number of the status bar. */
private class MemoryItem(val text: String, val tag: String)

private fun statusItems(memory: IdeMemory, server: ServerMemory): List<MemoryItem> {
    val total = memory.totalBytes
    if (memory.sameProcess) {
        val both = total?.let { MemoryItem("$ONE_PROCESS ${memoryText(it)}", LanguageServerTags.STATUS_TOTAL_MEMORY) }
        return listOfNotNull(both)
    }
    val serverBytes = memory.serverBytes
    // A server whose OS does not tell the memory of a process still tells its heap, named as such.
    val serverText =
        if (serverBytes != null) {
            "Server ${memoryText(serverBytes)}"
        } else {
            "Server heap ${memoryText(server.heapUsedBytes)}"
        }
    return listOfNotNull(
        memory.uiBytes?.let { MemoryItem("UI ${memoryText(it)}", LanguageServerTags.STATUS_UI_MEMORY) },
        MemoryItem(serverText, LanguageServerTags.STATUS_SERVER_MEMORY),
        total?.let { MemoryItem("Total ${memoryText(it)}", LanguageServerTags.STATUS_TOTAL_MEMORY) },
    )
}

private fun memoryTooltip(memory: IdeMemory, server: ServerMemory): String {
    val heap = "heap ${memoryText(server.heapUsedBytes)} of ${memoryText(server.heapMaxBytes)}"
    val processes =
        if (memory.sameProcess) {
            listOf("UI and IDE server, in one process: ${textOf(memory.totalBytes)} (server $heap)")
        } else {
            listOf(
                "UI process: ${textOf(memory.uiBytes)}",
                "IDE server process: ${textOf(memory.serverBytes)} ($heap)",
                "Total: ${textOf(memory.totalBytes)}",
            )
        }
    val notes =
        listOf(
            "Memory in use as the operating system counts it, not the heap alone",
            "The language servers are other processes, shown apart",
            "Click to open the Language Servers window",
        )
    return (processes + notes).joinToString("\n")
}

// A number of bytes, or that it cannot be measured on this OS.
private fun textOf(bytes: Long?): String = bytes?.let(::memoryText) ?: "n/a"

@Composable
private fun CpuLines(cpu: IdeCpu) {
    if (cpu.sameProcess) {
        MemoryLine("UI and IDE server · CPU", cpuOf(cpu.totalPercent), LanguageServerTags.CPU_TOTAL)
    } else {
        MemoryLine("UI process · CPU", cpuOf(cpu.uiPercent), LanguageServerTags.CPU_UI)
        MemoryLine("IDE server process · CPU", cpuOf(cpu.serverPercent), LanguageServerTags.CPU_SERVER)
        MemoryLine("Total · CPU", cpuOf(cpu.totalPercent), LanguageServerTags.CPU_TOTAL)
    }
}

// What the status bar says: one number for one process, else the parts that are known.
private fun cpuStatusText(cpu: IdeCpu): String? {
    val parts =
        if (cpu.sameProcess) {
            listOfNotNull(cpu.totalPercent?.let(::cpuText))
        } else {
            listOfNotNull(
                cpu.uiPercent?.let { "UI ${cpuText(it)}" },
                cpu.serverPercent?.let { "Server ${cpuText(it)}" },
            )
        }
    return if (parts.isEmpty()) null else "CPU ${parts.joinToString(" · ")}"
}

private fun cpuTooltip(cpu: IdeCpu): String {
    val processes =
        if (cpu.sameProcess) {
            listOf("UI and IDE server, in one process: ${cpuOf(cpu.totalPercent)}")
        } else {
            listOf(
                "UI process: ${cpuOf(cpu.uiPercent)}",
                "IDE server process: ${cpuOf(cpu.serverPercent)}",
                "Total: ${cpuOf(cpu.totalPercent)}",
            )
        }
    val notes =
        listOf(
            "Processor use over the last few seconds, in percent of one core: 100% is one core busy, 200% two",
            "The language servers are other processes, shown apart",
            "Click to open the Language Servers window",
        )
    return (processes + notes).joinToString("\n")
}

// A processor use, or that it cannot be measured (yet, or on this OS).
private fun cpuOf(percent: Int?): String = percent?.let(::cpuText) ?: "n/a"
