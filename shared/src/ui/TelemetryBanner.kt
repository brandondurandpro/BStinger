package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.TelemetryConsent

// What the measures hold, shown by "What is sent" (docs/telemetry.md has the full list).
private val SENT =
    listOf(
        "How long the IDE takes to start, open a project, a file, save, complete, search and run git.",
        "Whether each step ended well, was cancelled, or failed, with the kind of error only.",
        "The language of a file, a size bucket (<10k), local or remote, the protocol method of a request.",
        "The IDE version, the OS family and a random id of this installation.",
        "Never a path, a file name, its content, a search, a branch, a host nor who you are.",
    )

/**
 * Asks once, without blocking anything, whether the user shares anonymous performance measures. Nothing is
 * measured until they agree; the project menu changes the answer later.
 */
@Composable
fun TelemetryBanner(consent: TelemetryConsent) {
    if (!consent.asking) return
    var details by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(IdeColors.Panel, IdeShapes.Large)
            .padding(12.dp)
            .testTag(AppTags.TELEMETRY_BANNER),
    ) {
        Label("Help make Kotlin Editor faster", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Label(
            "Share anonymous measures of how long the IDE takes? No code, path or name ever leaves this machine.",
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 2,
        )
        if (details) SentList()
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IdeButton("Share", { consent.choose(true) }, Modifier.testTag(AppTags.TELEMETRY_ACCEPT), primary = true)
            IdeButton("No, thanks", { consent.choose(false) }, Modifier.testTag(AppTags.TELEMETRY_DECLINE))
            val toggle = if (details) "Hide" else "What is sent"
            Label(
                toggle,
                Modifier.clickable { details = !details }.testTag(AppTags.TELEMETRY_DETAILS).padding(4.dp),
                color = IdeColors.Link,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SentList() {
    Column(Modifier.padding(top = 8.dp).testTag(AppTags.TELEMETRY_SENT)) {
        SENT.forEach { line -> Label("•  $line", color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2) }
    }
}
