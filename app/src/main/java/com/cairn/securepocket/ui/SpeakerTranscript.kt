package com.cairn.securepocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SpeakerTranscript(record: VaultStore.Record, playback: RecordingPlayback, busy: Boolean, transcript: String?, modifier: Modifier = Modifier) {
    var labels by remember(record) { mutableStateOf<Speakers?>(null) }
    var loading by remember(record) { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var roster by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(record) {
        try { labels = Graph.notes.analyzeSpeakers(record) }
        catch (e: Exception) { error = e.localizedMessage ?: "Could not find speech segments." }
        catch (_: LinkageError) { error = "Speaker analysis is unavailable on this phone." }
        finally { loading = false }
    }
    val data = labels
    if (roster) WhoDialog(record, data?.people.orEmpty()) {
        roster = false
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { record.readSpeakers()?.people } }.getOrNull()
            if (saved != null) labels = labels?.copy(people = saved)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { roster = true }, enabled = !applying) { Text("Who?") }
            Button(enabled = data != null && !busy && !applying, onClick = {
                val selected = labels ?: return@Button
                applying = true
                playback.pause()
                scope.launch {
                    runCatching { Graph.notes.applySpeakers(record, selected) }
                        .onSuccess { labels = selected.withVoiceMatches(); error = "" }
                        .onFailure { error = it.message ?: "Could not save speaker labels." }
                    applying = false
                }
        }) { Text(if (applying) "Saving…" else "Save transcript") }
        }
        if (loading) {
            Text("Finding speech segments…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (data == null) {
            if (!transcript.isNullOrBlank()) LazyColumn(Modifier.fillMaxSize()) { item { Text(transcript) } }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(data.segments) { index, segment ->
                    var menu by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = playback.ready && !applying && segment.end > segment.start, onClick = {
                                playback.playClip(index, (segment.start * 1000).toLong(), (segment.end * 1000).toLong())
                            }) { Text(if (playback.clip == index) "Pause" else "Play ${speakerTime(segment.start)}") }
                            Box {
                                OutlinedButton(enabled = !applying, onClick = { menu = true }) {
                                    Text(segment.name?.let { if (segment.manual) it else "$it (matched)" } ?: "Speaker")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    (listOf<String?>(null) + data.people).forEach { name ->
                                        DropdownMenuItem(text = { Text(name ?: "Unidentified speaker") }, onClick = {
                                            labels = data.copy(segments = data.segments.mapIndexed { i, current ->
                                                if (i == index) current.copy(name = name, manual = true) else current
                                            })
                                            menu = false
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Add people…") }, onClick = { menu = false; roster = true })
                                }
                            }
                        }
                        var editedText by remember(record, index, segment.text) { mutableStateOf(segment.text) }
                        BasicTextField(
                            value = editedText,
                            onValueChange = { value ->
                                editedText = value
                                labels = labels?.copy(segments = labels!!.segments.mapIndexed { i, current ->
                                    if (i == index) current.copy(text = value) else current
                                })
                            },
                            enabled = !applying,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
