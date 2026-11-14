package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.ServerMemory
import org.example.kotlineditor.state.LanguageServersState
import org.example.kotlineditor.state.memoryText

/** The same numbers in the Language Servers window, as a table: a row per process, memory and CPU in columns. */
@Composable
internal fun IdeMemorySection(state: LanguageServersState) {
    val server = state.status.server
    val memory = state.memory
    val cpu = state.cpu
    Column(
        SECTION.testTag(LanguageServerTags.LANGUAGE_SERVERS_IDE),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ResourceHeader()
        if (memory.sameProcess) {
            ResourceRow(
                "UI + IDE server (one process)",
                Resource(textOf(memory.totalBytes), LanguageServerTags.MEMORY_TOTAL),
                Resource(cpuOf(cpu.totalPercent), LanguageServerTags.CPU_TOTAL),
            )
        } else {
            ResourceRow(
                "UI process",
                Resource(textOf(memory.uiBytes), LanguageServerTags.MEMORY_UI),
                Resource(cpuOf(cpu.uiPercent), LanguageServerTags.CPU_UI),
            )
            ResourceRow(
                "IDE server process",
                Resource(textOf(memory.serverBytes), LanguageServerTags.MEMORY_SERVER),
                Resource(cpuOf(cpu.serverPercent), LanguageServerTags.CPU_SERVER),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            ResourceRow(
                "Total",
                Resource(textOf(memory.totalBytes), LanguageServerTags.MEMORY_TOTAL),
                Resource(cpuOf(cpu.totalPercent), LanguageServerTags.CPU_TOTAL),
                total = true,
            )
        }
        if (server.heapMaxBytes > 0) HeapUsage(server)
    }
}

// A number of a table row, with the tag the tests read it by.
private class Resource(val text: String, val tag: String)

private val VALUE_WIDTH = 96.dp

// The titles of the two columns, over their numbers.
@Composable
private fun ResourceHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("IDE resources", Modifier.weight(1f))
        for (title in listOf("Memory", "CPU")) {
            Box(Modifier.width(VALUE_WIDTH), contentAlignment = Alignment.CenterEnd) { SectionLabel(title) }
        }
    }
}

// One process: its name, then its memory and its CPU, aligned on the right; the total row stands out.
@Composable
private fun ResourceRow(name: String, memory: Resource, cpu: Resource, total: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(
            name,
            Modifier.weight(1f),
            color = if (total) IdeColors.Text else IdeColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (total) FontWeight.SemiBold else null,
        )
        for (value in listOf(memory, cpu)) {
            Box(Modifier.width(VALUE_WIDTH), contentAlignment = Alignment.CenterEnd) {
                MonoValue(value.text, Modifier.testTag(value.tag))
            }
        }
    }
}

// The heap of the IDE server's JVM: used over the maximum, and how full it is.
@Composable
private fun HeapUsage(server: ServerMemory) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MemoryLine("IDE server heap", "${memoryText(server.heapUsedBytes)} / ${memoryText(server.heapMaxBytes)}")
        ProgressBar(server.heapUsedBytes.toFloat() / server.heapMaxBytes, Modifier.fillMaxWidth())
    }
}
