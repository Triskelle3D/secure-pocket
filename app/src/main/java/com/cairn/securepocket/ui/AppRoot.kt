package com.cairn.securepocket.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cairn.securepocket.Graph
import com.cairn.securepocket.isSavedCopyOf
import com.cairn.securepocket.vault.title
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.transport.Intent
import com.cairn.securepocket.ui.theme.Crimson
import com.cairn.securepocket.ui.theme.Emerald
import com.cairn.securepocket.ui.theme.GhostFill
import com.cairn.securepocket.ui.theme.Hairline
import com.cairn.securepocket.ui.theme.HairlineStrong
import com.cairn.securepocket.ui.theme.JetBrainsMono
import androidx.compose.material3.MaterialTheme as M3Theme
import com.cairn.securepocket.ui.theme.LabelCaps
import com.cairn.securepocket.ui.theme.LabelSm
import com.cairn.securepocket.ui.theme.Obsidian
import com.cairn.securepocket.ui.theme.OpticalWhite
import com.cairn.securepocket.ui.theme.SlateText
import com.cairn.securepocket.ui.theme.TintError
import com.cairn.securepocket.ui.theme.WhisperTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SecurePocketTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.cairn.securepocket.vault.SecurePreferences.open(context, "settings") }
    var dark by remember { mutableStateOf(prefs.getString("app_theme", "dark") != "light") }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "app_theme") dark = prefs.getString("app_theme", "dark") != "light"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    WhisperTheme(dark, content)
}

data class NavDest(val route: String, val label: String, val icon: ImageVector)

private val NAV = listOf(
    NavDest("device", "DEVICE", Icons.Outlined.CellTower),
    NavDest("library", "LIBRARY", Icons.Outlined.GraphicEq),
    NavDest("settings", "SETTINGS", Icons.Outlined.Tune),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    SecurePocketTheme {
        Scaffold(
            containerColor = Obsidian,
            topBar = { TopBar() },
            bottomBar = { BottomNav(nav) },
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = "device",
                modifier = Modifier.padding(pad),
            ) {
                composable("device") { DeviceScreen() }
                composable("library") { LibraryScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun TopBar() {
    val conn by Graph.runner.connection.collectAsState()
    val health by Graph.runner.health.collectAsState()
    val recording by Graph.runner.recording.collectAsState()
    val battery = (health["Battery"] as? Response.Battery)?.pct
    val liveMode = (health["PocketMode"] as? Response.Event)?.name?.let {
        when (it) { "REC&CALL" -> "Call"; "REC&CON" -> "Normal"; else -> null }
    }
    val mode = liveMode ?: Graph.runner.lastPocketMode()
    Row(
        modifier = Modifier.fillMaxWidth().background(Obsidian)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (recording) Crimson else if (conn == "READY") Emerald else SlateText),
        )
        Spacer(Modifier.width(6.dp))
        Text(when {
            recording -> "Recording"
            conn == "READY" -> liveMode ?: mode?.let { "Last $it" } ?: "Mode unknown"
            else -> conn.substringBefore(":").lowercase()
        }, style = LabelSm.copy(color = if (recording) Crimson else SlateText))
        Spacer(Modifier.weight(1f))
        if (battery != null && battery >= 0) {
            Icon(Icons.Outlined.BatteryStd, "battery", tint = if (battery <= 20) Crimson else OpticalWhite, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("$battery%", style = LabelSm.copy(color = if (battery <= 20) Crimson else Emerald))
        }
    }
}

@Composable
private fun BottomNav(nav: androidx.navigation.NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    Row(
        modifier = Modifier.fillMaxWidth().background(Obsidian).border(1.dp, Hairline)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NAV.forEach { d ->
            val selected = backStack?.destination?.hierarchy?.any { it.route == d.route } == true
            val tint = if (selected) OpticalWhite else SlateText
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        nav.navigate(d.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Icon(d.icon, d.label, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text(d.label, style = LabelCaps.copy(color = tint))
            }
        }
    }
}

@Composable
fun DeviceScreen() {
    val health by Graph.runner.health.collectAsState()
    val conn by Graph.runner.connection.collectAsState()
    val recording by Graph.runner.recording.collectAsState()
    val bytes by Graph.runner.recordingBytes.collectAsState()
    val downloading by Graph.runner.downloading.collectAsState()
    val lastCapture by Graph.runner.lastCapture.collectAsState()
    val sim = Graph.transport is com.cairn.securepocket.transport.Simulator
    var denied by remember { mutableStateOf(false) }
    val required = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val blePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        denied = required.any { perm -> ctx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!denied) Graph.runner.submit(Intent.Connect)
    }
    val ready = conn == "READY"
    val connecting = conn == "SCANNING" || conn == "CONNECTING"
    val connect = {
        if (sim || required.all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
            Graph.runner.submit(Intent.Connect)
        else blePermLauncher.launch(required)
    }

    val wifi = health["WifiStatus"] as? Response.WifiStatus
    val battery = (health["Battery"] as? Response.Battery)?.pct?.takeIf { it >= 0 }
    val fw = health["FW"] as? Response.VersionInfo
    val space = health["Space"] as? Response.Space
    val isolated = wifi != null && wifi.savedNetworks == 0
    val violated = wifi != null && wifi.savedNetworks > 0

    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        if (recording) {
            seconds = 0
            while (true) { delay(1000); seconds++ }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("DEVICE", style = LabelCaps.copy(color = SlateText))
                Spacer(Modifier.height(2.dp))
                Text("Pocket PKT-01", style = MaterialTheme.typography.headlineSmall, color = OpticalWhite)
                if (sim) {
                    Spacer(Modifier.height(4.dp))
                    Text("SIMULATOR DATA — not a live device", style = LabelSm.copy(color = Color(0xFFFFD9A0)))
                }
            }
        }

        Text(when {
            denied && !ready -> "Bluetooth permission required. Allow access in Settings, then retry."
            connecting -> if (conn == "SCANNING") "Searching for Pocket…" else "Connecting…"
            downloading -> "Importing recording…"
            recording -> "Recording · %02d:%02d · %,d bytes".format(seconds / 60, seconds % 60, bytes)
            ready -> "Connected"
            conn.startsWith("ERROR:") -> conn.removePrefix("ERROR: ")
            else -> "Wake your Pocket and keep it near this phone."
        }, style = MaterialTheme.typography.bodyLarge, color = OpticalWhite)
        when {
            recording -> PrimaryPill("Stop recording", Modifier.fillMaxWidth()) { Graph.runner.submit(Intent.Send("APP&STO")) }
            ready && !downloading -> PrimaryPill("Start recording", Modifier.fillMaxWidth()) {
                Graph.runner.submit(Intent.Send("APP&REC&SECEN"))
                Graph.runner.submit(Intent.Send("APP&STA"))
            }
            connecting -> GhostPill("Cancel connection", Modifier.fillMaxWidth()) { Graph.runner.submit(Intent.Disconnect) }
            !ready -> PrimaryPill(if (sim) "Connect simulator" else "Connect Pocket", Modifier.fillMaxWidth(), connect)
        }
        if (ready && !recording && !downloading) {
            GhostPill("Disconnect", Modifier.fillMaxWidth()) { Graph.runner.submit(Intent.Disconnect) }
        }
        FindPocket(ready)
        if (ready) PocketInventory(recording)
        // Isolation status — compact chip, or loud banner on violation (safety kept)
        if (violated) {
            Surface(color = Color(0x33E11D48), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "VIOLATION — recorder has ${wifi.savedNetworks} Wi-Fi setting(s); it can upload",
                    color = TintError, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (isolated) Emerald else SlateText))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isolated -> "No saved Wi-Fi networks"
                        wifi == null -> "Wi-Fi status unknown"
                        else -> ""
                    },
                    style = LabelCaps.copy(color = if (isolated) Emerald else SlateText),
                )
            }
        }

        if (fw != null && fw.raw != "1.8.9") {
            Surface(color = Color(0x33FFD54F), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text("FIRMWARE CHANGED — baseline 1.8.9, now ${fw.raw}",
                    color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
            }
        }

        // Stats pair
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatCard("BATTERY", battery?.let { "$it%" } ?: "—", "charge remaining", Modifier.weight(1f))
            StatCard(
                "POCKET STORAGE",
                storageLabel(space, used = true),
                storageLabel(space, used = false),
                Modifier.weight(1f),
            )
        }

        if (ready && !recording && !downloading) {
            lastCapture?.let { Text(it, style = LabelSm) }
            GhostPill("Refresh device info", Modifier.fillMaxWidth()) { Graph.runner.syncNow() }
        }
        DiagnosticsDisclosure(health)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PocketInventory(recording: Boolean) {
    val runner = Graph.runner
    val files by runner.inventory.collectAsState()
    val local by runner.records.collectAsState()
    val busy by runner.downloading.collectAsState()
    val message by runner.transfer.collectAsState()
    val notesVersion by Graph.notes.changed.collectAsState()
    val copies = remember(files, local, notesVersion) {
        files.associateWith { item ->
            local.filter { it.isSavedCopyOf(item) }
                .maxByOrNull { it.verification == com.cairn.securepocket.vault.VaultStore.Verification.MATCHED }

        }
    }
    var open by remember { mutableStateOf(false) }
    var importProblem by remember { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<com.cairn.securepocket.protocol.DeviceFile?>(null) }
    var renaming by remember { mutableStateOf<com.cairn.securepocket.protocol.DeviceFile?>(null) }
    fun label(item: com.cairn.securepocket.protocol.DeviceFile): String = copies[item]?.title
        ?: Graph.notes.pocketTitle(item.wireId) ?: if (item.wireId.startsWith("PH")) "Call" else "Conversation"
    renaming?.let { item ->
        RenameRecording(label(item), { newTitle ->
            val copy = copies[item]
            if (copy != null) Graph.notes.rename(copy, newTitle)
            Graph.notes.renamePocket(item.wireId, newTitle)
        }) { renaming = null }
    }
    selected?.let { item ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { selected = null },
            title = { Text("Delete from Pocket?") },
            text = { Text("Erase the Pocket recording from ${item.date} at ${item.startAt.toLocalTime()}? The app will compare it with the saved Library copy before deleting.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { selected = null; runner.deleteImported(item) }) {
                Text("Delete from Pocket", color = TintError)
            } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { selected = null }) { Text("Cancel") } })
    }
    importProblem?.let { problem ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importProblem = null },
            title = { Text("Server connection needed") },
            text = { Text(problem) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { importProblem = null }) { Text("OK") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = OpticalWhite,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable { open = !open },
        ) {
            Text("Recordings on Pocket · ${files.size}", color = Obsidian,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (message.isNotBlank()) Text(message, style = LabelSm)
        if (open) files.forEach { item ->
            val imported = copies[item] != null
            val savedOnServer = !imported && Graph.backup.isServerSaved(item)
            var menu by remember(item.wireId) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Box {
                        Text(label(item), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true },
                                onLongClickLabel = "Recording options"))
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menu = false; renaming = item
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete") },
                                enabled = imported && !busy && !recording &&
                                    Graph.transport is com.cairn.securepocket.transport.BleTransport,
                                onClick = { menu = false; selected = item })
                        }
                    }
                    Text("${item.startAt.toLocalDate()} · ${item.startAt.toLocalTime()} · ${item.lengthSeconds}s", style = LabelSm)
                }
                if (imported) {
                    Text("Imported", style = LabelSm)
                } else if (savedOnServer) {
                    Text("On server", style = LabelSm.copy(color = com.cairn.securepocket.ui.theme.Emerald))
                } else androidx.compose.material3.TextButton(enabled = !busy && !recording,
                    onClick = {
                        importScope.launch {
                            val issue = if (Graph.backup.usesRemoteStorage() && !Graph.backup.storesOnPhone())
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    Graph.backup.validateImportDestination()
                                }
                            else null
                            if (issue != null) importProblem = issue else runner.download(item)
                        }
                    }) { Text("Import") }
            }
        }
    }
}

private fun storageLabel(space: Response.Space?, used: Boolean): String {
    val free = space?.freeRaw?.toLongOrNull() ?: return "—"
    val total = space.totalRaw.toLongOrNull() ?: return "—"
    if (total <= 0 || free !in 0..total) return "—"
    val gb = (if (used) total - free else free) * 1048576.0 / 1e9
    val number = if (gb > 0 && gb < 0.01) "<0.01" else "%.2f".format(gb)
    return "≈ $number GB " + if (used) "used" else "free"
}

@Composable
private fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        Text(label, style = LabelCaps.copy(color = SlateText))
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.displaySmall, color = OpticalWhite)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = SlateText)
    }
}

@Composable
private fun PrimaryPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = OpticalWhite, modifier = modifier) {
        Text(
            text, color = Color(0xFF2F3132), fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun GhostPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, HairlineStrong), modifier = modifier,
    ) {
        Text(
            text, color = OpticalWhite, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DiagnosticsDisclosure(health: Map<String, Any?>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }.padding(vertical = 8.dp),
        ) {
            Text("DIAGNOSTICS & HEALTH", style = LabelCaps.copy(color = SlateText))
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                null, tint = SlateText, modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            if (health.isEmpty()) Text("no poll yet — tap Sync Device", style = LabelSm.copy(color = SlateText))
            healthRow("battery", (health["Battery"] as? Response.Battery)?.pct?.let { "$it%" })
            healthRow("space", (health["Space"] as? Response.Space)?.let { s -> s.freeRaw + " / " + s.totalRaw + " raw" })
            healthRow("FW", (health["FW"] as? Response.VersionInfo)?.raw)
            healthRow("BUILD", (health["BUILD"] as? Response.VersionInfo)?.raw)
            healthRow("VER", (health["VER"] as? Response.VersionInfo)?.raw)
            healthRow("wifi-fw", (health["WF"] as? Response.VersionInfo)?.raw)
            healthRow("MAC", (health["Mac"] as? Response.Mac)?.mac)
            healthRow("USB", (health["Usb"] as? Response.Usb)?.mode?.toString())
            healthRow("STE", (health["Ste"] as? Response.Ste)?.raw)
            healthRow("UPLE", (health["UploadState"] as? Response.UploadState)?.raw)
            healthRow("nets", (health["WifiStatus"] as? Response.WifiStatus)?.savedNetworks?.toString())
        }
    }
}

@Composable
private fun healthRow(label: String, raw: String?) {
    if (raw == null) return
    Row(
        Modifier.fillMaxWidth().border(1.dp, Hairline, RoundedCornerShape(0.dp)).padding(vertical = 6.dp),
    ) {
        Text(label, Modifier.padding(end = 12.dp), style = LabelSm.copy(color = SlateText))
        Spacer(Modifier.weight(1f))
        Text(raw, style = LabelSm.copy(color = OpticalWhite))
    }
}
