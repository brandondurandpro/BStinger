package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.StatusIndicator
import org.example.kotlineditor.state.StatusIndicators

private val CHECK_SIZE = 16.dp

// The button at the right end of the status bar, and its menu: which indicators the user keeps shown.
@Composable
internal fun StatusIndicatorsMenu(indicators: StatusIndicators) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { menuOpen = true }
                .testTag(AppTags.STATUS_INDICATORS)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Indicators", color = IdeColors.TextSecondary, fontSize = 12.sp)
            Chevron(expanded = menuOpen)
        }
        IdeDropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            for (indicator in StatusIndicator.entries) {
                val shown = indicators.isShown(indicator)
                // The menu stays open: several indicators are often changed at once.
                IdeMenuItem(
                    text = { Text(indicator.label, fontSize = 13.sp) },
                    onClick = { indicators.setShown(indicator, !shown) },
                    leadingIcon = { if (shown) CheckIcon() else Spacer(Modifier.size(CHECK_SIZE)) },
                    modifier = Modifier.testTag(AppTags.statusIndicator(indicator.name)),
                )
            }
        }
    }
}
