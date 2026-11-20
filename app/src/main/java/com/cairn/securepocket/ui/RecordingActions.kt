package com.cairn.securepocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.VaultStore
import com.cairn.securepocket.vault.readSpeakers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RenameRecording(title: String, save: (String) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf(title) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Rename recording") },
        text = {
            Column {
                OutlinedTextField(value, { if (it.length <= 160 && !it.contains('\n')) value = it },
                    label = { Text("Title") }, singleLine = true)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
            runCatching { save(value.trim()) }.onSuccess { close() }
                .onFailure { error = it.message ?: "Could not save title." }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
internal fun WhoDialog(record: VaultStore.Record, known: List<String>, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<String>?>(null) }
    var newName by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(record) {
        runCatching { withContext(Dispatchers.IO) { record.readSpeakers()?.people.orEmpty() } }
            .onSuccess { names = it }
            .onFailure { error = "Could not read participant names." }
    }
    fun add(name: String) {
        val current = names ?: return
        if (name.isNotBlank() && current.none { it.equals(name, true) }) names = current + name
        newName = ""
    }
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text("Who was there?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val people = names
                if (people != null) {
                    if (people.isNotEmpty()) Text(people.joinToString(", "))
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text("Choose a person") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val options = known.filter { it !in people }
                            if (options.isEmpty()) DropdownMenuItem(text = { Text("No other saved names") }, onClick = {}, enabled = false)
                            options.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { add(name); menu = false })
                            }
                        }
                    }
                    OutlinedTextField(newName, { if (it.length <= 80 && it.none(Char::isISOControl)) newName = it },
                        label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = newName.isNotBlank(), onClick = { add(newName.trim()) }) { Text("Add") }
                        if (people.isNotEmpty()) TextButton(onClick = { names = people.dropLast(1) }) { Text("Remove last") }
                    }
                    Text("Assign speech to these people in the transcript.", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = names != null && !saving, onClick = {
            val updated = names ?: return@TextButton
            saving = true
            scope.launch {
                runCatching { Graph.notes.saveRoster(record, updated) }.onSuccess { close() }
                    .onFailure { error = it.message ?: "Could not save participants." }
                saving = false
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close, enabled = !saving) { Text("Cancel") } })
}
