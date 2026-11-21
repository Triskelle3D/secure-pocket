package com.cairn.securepocket.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.BuildConfig
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.SecurePreferences
import com.cairn.securepocket.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saved = remember(context) { SecurePreferences.open(context, "settings") }
    val p = remember(context) {
        SecurePreferences.open(context, "settings_draft").also { copySettings(saved, it) }
    }
    var saveStatus by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val names = listOf("Transcription", "Summaries", "Library Location", "App")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.displaySmall, color = OpticalWhite)
        Text("Changes stay pending until you save.", style = MaterialTheme.typography.bodySmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 0..1) {
                    val i = row * 2 + column
                    FilterChip(selected = tab == i, onClick = { tab = i }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        label = { Text(names[i], style = MaterialTheme.typography.labelLarge) })
                }
            }
        }
        Surface(color = ElevatedCanvas, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (tab) {
                    0 -> {
                        Title("Speech to text")
                        Text("Choose how Pocket audio becomes text.", style = MaterialTheme.typography.bodyMedium)
                        val currentStage = p.getString("docling_mode", "Off") ?: "Off"
                        val doclingTranscribes = currentStage == "Pre" || currentStage == "Both"
                        val engine = Choice(p, "stt_provider", "Speech engine", listOf("On-device Vosk", "OpenAI", "Compatible server"), enabled = !doclingTranscribes)
                        if (doclingTranscribes) Text("Docling handles transcription. Speech engine settings are paused.", style = MaterialTheme.typography.bodySmall)
                        when (engine) {
                            "On-device Vosk" -> Text("English transcription on this phone.")
                            else -> key(engine) {
                                if (engine == "Compatible server") PrefField(p, "stt_url", "Speech API base URL (including /v1)", enabled = !doclingTranscribes)
                                PrefField(p, "stt_model", "Speech model", default = "whisper-1", enabled = !doclingTranscribes)
                                PrefField(p, if (engine == "OpenAI") "stt_openai_token" else "stt_token", "Speech API key", true, enabled = !doclingTranscribes)
                            }
                        }
                        PrefSwitch(p, "auto_transcribe", true, "Process new recordings on import")
                        HorizontalDivider()
                        HorizontalDivider()
                        Title("Docling formatting")
                        val stage = Choice(p, "docling_mode", "Docling stages", listOf("Off", "Pre", "Post", "Both"))
                        Text("Pre: transcribe audio. Post: format the summary. Both: use Docling for each.", style = MaterialTheme.typography.bodyMedium)
                        if (stage != "Off") {
                            PrefField(p, "docling_url", "Secure Pocket Link URL with Docling enabled")
                            PrefField(p, "docling_token", "Link token", true)
                        }
                        PrefSwitch(p, "allow_processing_servers", false, "Send audio or text to selected processing servers")
                    }
                    1 -> {
                        Title("Summary and title")
                        Text("Choose the model that writes recording summaries and titles.", style = MaterialTheme.typography.bodyMedium)
                        val provider = Choice(p, "llm_provider", "LLM provider", listOf("On-device LLM", "Off", "Ollama", "OpenAI", "OpenRouter", "Compatible server"))
                        if (provider == "On-device LLM") LocalModelPicker()
                        if (provider !in listOf("On-device LLM", "Off")) key(provider) {
                            if (provider in listOf("Ollama", "Compatible server")) PrefField(p, "llm_url",
                                if (provider == "Ollama") "Ollama server URL" else "LLM API base URL (including /v1)")
                            PrefField(p, "llm_model", "Model ID")
                            if (provider != "Ollama") PrefField(p, "llm_token_$provider", "LLM API key", true)
                        }
                        if (provider !in listOf("On-device LLM", "Off"))
                            PrefSwitch(p, "allow_processing_servers", false, "Allow sending text to this provider")
                        PrefSwitch(p, "auto_summary", true, "Summarize and title new recordings")
                        Text("Maximum summary points")
                        var count by remember { mutableIntStateOf(p.getInt("summary_points", 3)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in listOf(3,5,8)) FilterChip(count == n, {
                                count = n; p.edit().putInt("summary_points", n).apply()
                            }, { Text(n.toString()) })
                        }
                    }
                    2 -> {
                        Title("Library location")
                        var keepPhone by remember { mutableStateOf(p.getBoolean("store_phone", true)) }
                        var destination by remember {
                            mutableStateOf(p.getString("library_destination", "Phone").orEmpty().let { if (it == "NAS backup") "NAS" else it })
                        }
                        Text("Choose where new recordings go.", style = MaterialTheme.typography.bodyMedium)
                        DestinationOption("Phone", "Keep recordings in the phone library.", destination == "Phone") {
                            destination = "Phone"
                            keepPhone = true
                            p.edit().putString("library_destination", "Phone").putBoolean("store_phone", true).apply()
                        }
                        DestinationOption("Secure Pocket Link", "Send recordings to a PC running Secure Pocket Link.", destination == "Secure Pocket Link") {
                            destination = "Secure Pocket Link"
                            p.edit().putString("library_destination", destination).apply()
                        }
                        DestinationOption("NAS", "Send recordings to a NAS over home Wi-Fi.", destination == "NAS") {
                            destination = "NAS"
                            p.edit().putString("library_destination", destination).apply()
                        }
                        if (destination != "Phone") {
                            SwitchRow("Also keep a copy on this phone", keepPhone) {
                                keepPhone = it
                                p.edit().putBoolean("store_phone", it).apply()
                            }
                            Text(if (keepPhone) "A phone copy is saved first. Remote backup can run when the destination is available."
                                else "Server only. Connect to the selected destination before importing.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        var activeMethod by remember(destination) { mutableStateOf(p.getString("nas_method", "WebDAV") ?: "WebDAV") }
                        var showMethodOptions by remember(destination) { mutableStateOf(false) }
                        if (destination == "Secure Pocket Link") {
                            Title("Secure Pocket Link connection")
                            PrefField(p, "link_base", "Secure Pocket Link URL")
                            PrefField(p, "link_token", "Link token", true)
                        }
                        if (destination == "NAS") {
                            Title("NAS connection")
                            MethodSection("Connect with WebDAV", activeMethod == "WebDAV", showMethodOptions && activeMethod == "WebDAV") {
                                val wasSelected = activeMethod == "WebDAV"
                                activeMethod = "WebDAV"
                                showMethodOptions = if (wasSelected) !showMethodOptions else true
                                p.edit().putString("nas_method", activeMethod).apply()
                            }
                            MethodSection("Connect through Secure Pocket Link", activeMethod == "Secure Pocket Link", showMethodOptions && activeMethod == "Secure Pocket Link") {
                                val wasSelected = activeMethod == "Secure Pocket Link"
                                activeMethod = "Secure Pocket Link"
                                showMethodOptions = if (wasSelected) !showMethodOptions else true
                                p.edit().putString("nas_method", activeMethod).apply()
                            }
                            if (showMethodOptions && activeMethod == "WebDAV") {
                                Text("Use a writable WebDAV folder on your NAS.", style = MaterialTheme.typography.bodyMedium)
                                PrefField(p, "nas_url", "WebDAV folder URL")
                                PrefField(p, "nas_user", "NAS username")
                                PrefField(p, "nas_token", "NAS password", true)
                            } else if (showMethodOptions && activeMethod == "Secure Pocket Link") {
                                Text("Choose this when Secure Pocket Link runs on a PC connected to your NAS.", style = MaterialTheme.typography.bodyMedium)
                                PrefField(p, "nas_url", "Secure Pocket Link URL")
                                PrefField(p, "nas_token", "Link token", true)
                            }
                            PrefField(p, "nas_ssid", "Only sync on this Wi-Fi network")
                            val permission = androidx.activity.compose.rememberLauncherForActivityResult(
                                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                            ) { Graph.backup.request() }
                            val context = LocalContext.current
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                permission.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
                            }) { Text("Allow Wi-Fi name access") }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)))
                            }) { Text("Set background permission") }
                            Text("Needed for automatic backups while the app is closed.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (destination != "Phone") {
                            val status by Graph.backup.status.collectAsState()
                            val scope = rememberCoroutineScope()
                            var connectionStatus by remember { mutableStateOf("") }
                            val backupKeys = setOf("library_destination", "store_phone", "link_base", "link_token", "nas_url", "nas_user", "nas_token", "nas_method", "nas_ssid")
                            val backupSettingsChanged = backupKeys.any { saved.all[it] != p.all[it] }
                            val savedDestination = saved.getString("library_destination", "Phone") ?: "Phone"
                            val savedHasPhoneCopy = saved.getBoolean("store_phone", true)
                            Text("Test connection checks these current settings. Save before imports or backups use them.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                connectionStatus = "Testing connection…"
                                scope.launch {
                                    val problem = Graph.backup.validateImportDestination(p)
                                    connectionStatus = problem ?: "Connection successful. Save settings to use this destination."
                                }
                            }) { Text("Test connection") }
                            if (connectionStatus.isNotBlank()) Text(connectionStatus, style = MaterialTheme.typography.bodySmall)
                            Button(modifier = Modifier.fillMaxWidth(), enabled = status != "Checking library…" &&
                                savedDestination != "Phone" && savedHasPhoneCopy && !backupSettingsChanged, onClick = {
                                scope.launch { Graph.backup.sync(force = true) }
                            }) { Text("Back up now · saved settings") }
                            if (backupSettingsChanged || !savedHasPhoneCopy || savedDestination == "Phone")
                                Text("Save settings with a phone copy enabled to back up the library.", style = MaterialTheme.typography.bodySmall)
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    3 -> {
                        Title("Appearance")
                        Text("Choose the app color theme.", style = MaterialTheme.typography.bodyMedium)
                        Choice(p, "app_theme", "Color theme", listOf("dark", "light"))
                        HorizontalDivider()
                        Title("About")
                        Text("Secure Pocket ${BuildConfig.VERSION_NAME}", style = LabelSm)
                    }
                }
            }
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val changes = p.all
            val previous = saved.all
            val editor = saved.edit()
            changes.forEach { (key, value) ->
                if (isSettingsPreference(key) && previous[key] != value) putPreference(editor, key, value)
            }
            if (editor.commit()) {
                saveStatus = "Settings saved."
                val backupKeys = setOf("library_destination", "store_phone", "link_base", "link_token", "nas_url", "nas_user", "nas_token", "nas_method", "nas_ssid")
                if (backupKeys.any { previous[it] != changes[it] }) {
                    Graph.backup.schedule()
                    Graph.backup.request()
                }
            } else saveStatus = "Could not save settings. Try again."
        }) { Text("Save settings") }
        if (saveStatus.isNotBlank()) Text(saveStatus, style = MaterialTheme.typography.bodySmall)
    }
}

private fun copySettings(source: SharedPreferences, target: SharedPreferences) {
    val editor = target.edit().clear()
    source.all.forEach { (key, value) -> putPreference(editor, key, value) }
    editor.apply()
}

private fun isSettingsPreference(key: String): Boolean = key in setOf(
    "stt_provider", "stt_url", "stt_model", "stt_openai_token", "stt_token", "auto_transcribe",
    "docling_mode", "docling_url", "docling_token", "allow_processing_servers", "llm_provider",
    "llm_url", "llm_model", "auto_summary", "summary_points", "library_destination", "store_phone",
    "link_base", "link_token", "nas_url", "nas_user", "nas_token", "nas_method", "nas_ssid", "app_theme",
) || key.startsWith("llm_token_")

private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
    when (value) {
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}

@Composable private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)
@Composable private fun PrefField(p: SharedPreferences, key: String, label: String, secret: Boolean = false, default: String = "", enabled: Boolean = true) {
    var value by remember(key) { mutableStateOf(p.getString(key,default).orEmpty()) }
    OutlinedTextField(value, { value = it; p.edit().putString(key, it).apply() },
        label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable private fun PrefSwitch(p: SharedPreferences, key: String, default: Boolean, label: String, enabled: Boolean = true) {
    var checked by remember(key) { mutableStateOf(p.getBoolean(key, default)) }
    SwitchRow(label, checked, enabled) { checked = it; p.edit().putBoolean(key, it).apply() }
}
@Composable private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable private fun Choice(p: SharedPreferences, key: String, label: String, options: List<String>,
    changed: () -> Unit = {}, enabled: Boolean = true): String {
    var value by remember(key) { mutableStateOf(p.getString(key, options.first()) ?: options.first()) }
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value.replaceFirstChar { it.uppercase() }, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Choose $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    value = option
                    p.edit().putString(key, option).apply()
                    open = false
                    changed()
                })
            }
        }
    }
    return value
}

@Composable private fun DestinationOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = onClick)
            Column(Modifier.padding(start = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun MethodSection(title: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        RadioButton(selected, onClick = onClick)
        Text(title, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse $title" else "Configure $title")
    }
}

@Composable private fun LocalModelPicker() {
    val context = LocalContext.current
    val model = remember(context) { com.cairn.securepocket.vault.OnDeviceModel(context) }
    val scope = rememberCoroutineScope()
    var selectedName by remember { mutableStateOf(model.displayName.takeIf { model.file.isFile }) }
    var error by remember { mutableStateOf("") }
    var copying by remember { mutableStateOf(false) }
    val select = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            copying = true
            error = ""
            val result = withContext(Dispatchers.IO) { runCatching { model.import(uri) } }
            if (result.isSuccess) selectedName = model.displayName
            else error = "Import failed: ${result.exceptionOrNull()?.localizedMessage ?: "Could not read this model file."}"
            copying = false
        }
    }
    Text("Summaries run on this phone. Import a CPU-compatible MediaPipe .task model to enable local summaries.")
    if (selectedName != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SELECTED MODEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(selectedName!!, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Ready for on-device summaries", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No model selected", style = MaterialTheme.typography.titleSmall)
                Text("Choose a .task file to enable local summaries.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(enabled = !copying, onClick = { select.launch(arrayOf("*/*")) }) {
        Text(if (copying) "Importing model…" else if (selectedName == null) "Choose model file" else "Change model")
    }
    if (copying) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (selectedName != null) TextButton(enabled = !copying, onClick = {
        model.remove()
        selectedName = null
        error = ""
    }) { Text("Remove local model") }
}
