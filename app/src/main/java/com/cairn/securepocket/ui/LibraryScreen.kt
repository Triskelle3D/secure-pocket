package com.cairn.securepocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
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
import com.cairn.securepocket.vault.title
import com.cairn.securepocket.vault.readSpeakers
import com.cairn.securepocket.vault.transcript
import com.cairn.securepocket.vault.summary
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
    val notesVersion by Graph.notes.changed.collectAsState()
    val records = Graph.runner.records.collectAsState().value
    val health by Graph.runner.health.collectAsState()
    val space = health["Space"] as? Response.Space
    var mode by rememberSaveable { mutableStateOf(LibraryMode.TRANSCRIPTIONS) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<com.cairn.securepocket.vault.LinkFile>>(emptyList()) }
    var linkMsg by remember { mutableStateOf<String?>(null) }
    var linkTick by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val linkOn = Graph.link.enabled()
    val serverOnly = Graph.backup.usesRemoteStorage() && !Graph.backup.storesOnPhone()
    LaunchedEffect(linkTick, linkOn) {
        if (!linkOn) { remote = emptyList(); linkMsg = null; return@LaunchedEffect }
        linkMsg = "Connecting to Secure Pocket Link…"
        try {
            remote = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Graph.link.fetchIndex() }
            linkMsg = null
        } catch (e: Exception) {
            remote = emptyList()
            linkMsg = "Could not reach Link. Check the server and try Refresh."
        }
    }
    val remoteFiltered = remote.filter { it.name.contains(query, ignoreCase = true) }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRemote by remember { mutableStateOf<com.cairn.securepocket.vault.LinkFile?>(null) }
    selectedRemote?.let { RemoteRecordingViewer(it) { selectedRemote = null } }
    records.firstOrNull { it.mp3.absolutePath == selected }?.let { record ->
        key(record.mp3.absolutePath, mode) {
            RecordingDetail(record, mode == LibraryMode.SUMMARIES) { selected = null }
        }
    }
    val titles = remember(records, notesVersion) { records.associate { it.mp3.absolutePath to it.title } }
    var people by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(records, notesVersion) {
        people = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            records.associate { it.mp3.absolutePath to runCatching { it.readSpeakers()?.people.orEmpty() }.getOrDefault(emptyList()) }
        }
    }
    val knownPeople = people.values.flatten().distinct().sorted()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val filtered = if (query.isBlank()) records else records.filter { rec ->
        val hay = "${titles[rec.mp3.absolutePath]} ${rec.date} ${rec.startAt} ${rec.localId} ${rec.startAt.toLocalTime().format(fmt)} ${people[rec.mp3.absolutePath].orEmpty().joinToString(" ")}"
        hay.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (serverOnly) "${records.size} RECORDINGS ON PHONE"
                else "${records.size} RECORDINGS · ${"%,d".format(records.sumOf { it.bytes } / 1024)} KB LOCAL",
                style = LabelCaps.copy(color = SlateText),
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
            Spacer(Modifier.width(6.dp))
            Text(if (serverOnly) "SERVER ONLY" else "VAULT LOCAL", style = LabelSm.copy(color = Emerald))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.displaySmall, color = OpticalWhite, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                searchVisible = !searchVisible
                if (!searchVisible) query = ""
            }) {
                Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searchVisible) "Hide search" else "Search library", tint = OpticalWhite)
            }
        }
        if (searchVisible) {
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
                    if (query.isEmpty()) Text("Search by title, person or date…", style = LabelSm.copy(color = SlateText))
                    inner()
                }
            },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModePill("TRANSCRIPTIONS", mode == LibraryMode.TRANSCRIPTIONS) { mode = LibraryMode.TRANSCRIPTIONS }
            ModePill("SUMMARIES", mode == LibraryMode.SUMMARIES) { mode = LibraryMode.SUMMARIES }
        }
        Spacer(Modifier.height(16.dp))
        if (filtered.isEmpty() && !linkOn) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (records.isEmpty()) "NO LOCAL RECORDINGS" else "NO MATCHES",
                    style = LabelCaps.copy(color = SlateText))
                Spacer(Modifier.height(6.dp))
                Text(if (records.isEmpty()) "Connect your Pocket on the Device tab." else "clear the filter",
                    style = MaterialTheme.typography.bodySmall, color = SlateText)
            }
        }
        if (!(filtered.isEmpty() && remoteFiltered.isEmpty() && !linkOn)) {
            LazyColumn(Modifier.weight(1f)) {
                if (linkOn && serverOnly) remoteLinkSection(
                    remoteFiltered, records, linkMsg,
                    onRefresh = { linkTick++ },
                    openRemote = { selectedRemote = it },
                )
                items(filtered, key = { it.mp3.absolutePath }) { rec -> LibraryRow(rec, mode, titles.getValue(rec.mp3.absolutePath), knownPeople) { selected = rec.mp3.absolutePath } }
                if (linkOn && !serverOnly) remoteLinkSection(
                    remoteFiltered, records, linkMsg,
                    onRefresh = { linkTick++ },
                    onPull = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val sum = Graph.runner.importFromLink()
                            linkMsg = "${sum.imported} in · ${sum.skipped} dup · ${sum.failed} failed"
                            linkTick++
                        }
                    },
                    openRemote = { selectedRemote = it },
                )
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.remoteLinkSection(
    remote: List<com.cairn.securepocket.vault.LinkFile>,
    records: List<VaultStore.Record>,
    message: String?,
    onRefresh: () -> Unit,
    onPull: (() -> Unit)? = null,
    openRemote: (com.cairn.securepocket.vault.LinkFile) -> Unit,
) {
    val shown = remote.filter { file -> file.sha16.isEmpty() || records.none { it.sha256.startsWith(file.sha16) } }
    item {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
            Spacer(Modifier.width(6.dp))
            Text("Secure Pocket Link", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        message?.let { Spacer(Modifier.height(4.dp)); Text(it, style = LabelSm.copy(color = SlateText)) }
        if (message == null && shown.isEmpty()) Text("No server recordings to show.", style = LabelSm)
        if (onPull != null && shown.isNotEmpty()) TextButton(onClick = onPull) { Text("Save new recordings to phone") }
    }
    items(shown) { file ->
        LinkRow(file) { openRemote(file) }
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
private fun LinkRow(f: com.cairn.securepocket.vault.LinkFile, open: () -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val dt = com.cairn.securepocket.vault.LinkClient.dateOf(f.bundle ?: f.name)
    Column(Modifier.fillMaxWidth().border(1.dp, Hairline).clickable(onClick = open).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${dt.toLocalDate()} · ${dt.toLocalTime().format(fmt)}",
                style = LabelSm.copy(color = SlateText), modifier = Modifier.weight(1f),
            )
            Text("LINK", style = LabelCaps.copy(color = Emerald))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.name.removeSuffix(".mp3").take(30),
                style = MaterialTheme.typography.bodyMedium, color = OpticalWhite,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${"%,d".format(f.bytes / 1024)} KB · sha:${f.sha16.take(8)}",
                style = LabelSm.copy(color = SlateText))
        }
    }
}

@Composable
private fun LibraryRow(rec: VaultStore.Record, mode: LibraryMode, title: String, knownPeople: List<String>, open: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var roster by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    if (rename) RenameRecording(title, { Graph.notes.rename(rec, it) }) { rename = false }
    if (roster) WhoDialog(rec, knownPeople) { roster = false }
    if (delete) AlertDialog(onDismissRequest = { delete = false },
        title = { Text(if (mode == LibraryMode.SUMMARIES) "Delete summary?" else "Delete from phone library?") },
        text = {
            Text(if (mode == LibraryMode.SUMMARIES) "This removes the saved summary from this phone."
                else "This removes this phone’s audio, transcript, summary and speaker labels. The Pocket and backups keep their copies.")
        },
        confirmButton = { TextButton(onClick = {
            delete = false
            runCatching { Graph.notes.deleteLocal(rec, summaryOnly = mode == LibraryMode.SUMMARIES) }
                .onFailure { error = it.message ?: "Could not delete from Library." }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("Could not delete") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
    val time = rec.startAt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = { menu = true }, onLongClickLabel = "Recording options").padding(vertical = 14.dp),
    ) {
        Text("$time · ${rec.date}", style = LabelSm.copy(color = SlateText))
        Spacer(Modifier.height(6.dp))
        Box {
            Text(title, style = MaterialTheme.typography.titleMedium, color = OpticalWhite)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete = true })
                if (mode == LibraryMode.SUMMARIES) DropdownMenuItem(text = { Text("Re-summarize") }, onClick = {
                    menu = false
                    Graph.notes.process(rec, makeSummary = true, replace = true)
                    open()
                })
                else DropdownMenuItem(text = { Text("Who?") }, onClick = { menu = false; roster = true })
            }
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
            if (mode == LibraryMode.TRANSCRIPTIONS) "Play audio · " + if (rec.transcript.isFile) "Read transcript" else "Transcribe"
            else if (rec.summary.isFile) "Read summary  ›" else "Create summary  ›",
            style = MaterialTheme.typography.bodySmall, color = SlateText,
        )
    }
}
