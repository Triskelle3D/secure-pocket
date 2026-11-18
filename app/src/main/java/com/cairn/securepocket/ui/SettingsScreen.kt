package com.cairn.securepocket.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.ui.theme.Crimson
import com.cairn.securepocket.ui.theme.Emerald
import com.cairn.securepocket.ui.theme.Hairline
import com.cairn.securepocket.ui.theme.LabelCaps
import com.cairn.securepocket.ui.theme.LabelSm
import com.cairn.securepocket.ui.theme.OpticalWhite
import com.cairn.securepocket.ui.theme.SlateText

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("SETTINGS", style = LabelCaps.copy(color = SlateText))
        Text("Zero egress", style = MaterialTheme.typography.displaySmall, color = OpticalWhite)
        Text(
            "This build contains no network code paths. Cloud AI egress: OFF.",
            style = MaterialTheme.typography.bodyMedium, color = SlateText,
        )
        Row(
            Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp),
        ) {
            Text("CLOUD PROVIDERS", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
            Text("ALL DISABLED", style = LabelSm.copy(color = Crimson))
        }
        ProviderRow("OpenAI")
        ProviderRow("OpenRouter")
        ProviderRow("Anthropic")
        ProviderRow("Custom endpoint")
        ProviderRow("LAN endpoint")
        Row(
            Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp),
        ) {
            Text("DEVICE SHUT", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
            Text("HIDDEN IN v0", style = LabelSm.copy(color = SlateText))
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp),
        ) {
            Text("TRANSPORT", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
            Text("SIMULATOR · SCRIPTED", style = LabelSm.copy(color = Emerald))
        }
        Text(
            "protocol ground truth: docs/PROTOCOL.md",
            style = MaterialTheme.typography.bodySmall, color = SlateText,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderRow(name: String) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = OpticalWhite, modifier = Modifier.weight(1f))
        Text("DISABLED · v1", style = LabelSm.copy(color = SlateText))
    }
}
