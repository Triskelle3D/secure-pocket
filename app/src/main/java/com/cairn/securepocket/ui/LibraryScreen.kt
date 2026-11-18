package com.cairn.securepocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.Graph
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.vault.VaultStore
import com.cairn.securepocket.ui.theme.Crimson
import com.cairn.securepocket.ui.theme.Emerald
import com.cairn.securepocket.ui.theme.GhostFill
import com.cairn.securepocket.ui.theme.Hairline
import com.cairn.securepocket.ui.theme.LabelCaps
import com.cairn.securepocket.ui.theme.LabelSm
import com.cairn.securepocket.ui.theme.OpticalWhite
import com.cairn.securepocket.ui.theme.SlateText
import java.time.format.DateTimeFormatter

private enum class LibraryMode { TRANSCRIPTIONS, SUMMARIES }

@Composable
fun LibraryScreen() {
    val records = Graph.runner.records.collectAsState().value
    val health by Graph.runner.health.collectAsState()
    val space = health["Space"] as? Response.Space
    var mode by rememberSaveable { mutableStateOf(LibraryMode.TRANSCRIPTIONS) }
    var query by rememberSaveable { mutableStateOf("") }

    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val filtered = if (query.isBlank()) records else records.filter { rec ->
        val hay = "${rec.date} ${rec.startAt} ${rec.localId} ${rec.startAt.toLocalTime().format(fmt)}"
        hay.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${records.size} RECORDINGS · ${"%,d".format(records.sumOf { it.bytes } / 1024)} KB LOCAL",
                style = LabelCaps.copy(color = SlateText),
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
            Spacer(Modifier.width(6.dp))
            Text("VAULT LOCAL", style = LabelSm.copy(color = Emerald))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.displaySmall, color = OpticalWhite, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // Frameless search line, focus hairline per design
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = OpticalWhite, fontFamily = androidx.compose.ui.text.font.FontFamily.Default),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Column(Modifier.fillMaxWidth().border(1.dp, Hairline, RoundedCornerShape(0.dp)).padding(vertical = 8.dp)) {
                    if (query.isEmpty()) Text("filter by date, time, id…", style = LabelSm.copy(color = SlateText))
                    inner()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModePill("TRANSCRIPTIONS", mode == LibraryMode.TRANSCRIPTIONS) { mode = LibraryMode.TRANSCRIPTIONS }
            ModePill("SUMMARIES", mode == LibraryMode.SUMMARIES) { mode = LibraryMode.SUMMARIES }
        }
        Spacer(Modifier.height(16.dp))
        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (records.isEmpty()) "NO LOCAL RECORDINGS" else "NO MATCHES",
                    style = LabelCaps.copy(color = SlateText))
                Spacer(Modifier.height(6.dp))
                Text(if (records.isEmpty()) "record from the Device tab" else "clear the filter",
                    style = MaterialTheme.typography.bodySmall, color = SlateText)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { rec -> LibraryRow(rec, mode) }
                item {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        space?.let { "INTERNAL FLASH ${it.freeRaw} / ${it.totalRaw} RAW" } ?: "INTERNAL FLASH UNKNOWN",
                        style = LabelCaps.copy(color = SlateText), modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("ZERO CLOUD DEPENDENCY ACTIVE",
                        style = LabelCaps.copy(color = SlateText), modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) GhostFill else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Hairline else Color.Transparent),
    ) {
        Text(
            label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = LabelCaps.copy(color = if (selected) OpticalWhite else SlateText),
        )
    }
}

@Composable
private fun LibraryRow(rec: VaultStore.Record, mode: LibraryMode) {
    val time = rec.startAt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    Column(
        Modifier.fillMaxWidth().border(1.dp, Hairline, RoundedCornerShape(0.dp)).padding(vertical = 14.dp),
    ) {
        Text("$time · ${rec.date}", style = LabelSm.copy(color = SlateText))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recording $time", style = MaterialTheme.typography.titleMedium, color = OpticalWhite, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            VerificationPill(rec)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%,d B".format(rec.bytes), style = LabelSm.copy(color = SlateText))
            rec.lengthSeconds?.let {
                Spacer(Modifier.width(10.dp))
                Text("▶ %02d:%02d".format(it / 60, it % 60), style = LabelSm.copy(color = OpticalWhite))
            }
            Spacer(Modifier.width(10.dp))
            Text("sha:${rec.sha256.take(8)}", style = LabelSm.copy(color = SlateText))
            if (rec.partial) {
                Spacer(Modifier.width(10.dp))
                Text("PARTIAL", style = LabelSm.copy(color = Color(0xFFFFD9A0)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (mode == LibraryMode.TRANSCRIPTIONS) "no transcript yet — on-device Whisper lands in v1"
            else "no summary yet — generated from transcript in v1",
            style = MaterialTheme.typography.bodySmall, color = SlateText,
        )
    }
}

@Composable
private fun VerificationPill(rec: VaultStore.Record) {
    val (text, fg, bg) = when {
        rec.verification == VaultStore.Verification.MATCHED ->
            Triple("ON DEVICE", Emerald, Color(0x1F10B981))
        rec.verification == VaultStore.Verification.MISMATCHED ->
            Triple("VERIFY FAILED", Crimson, Color(0x1FE11D48))
        else ->
            Triple("NOT VERIFIED", SlateText, Color(0x0FFFFFFF))
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = LabelCaps.copy(color = fg, fontWeight = FontWeight.Medium))
    }
}
