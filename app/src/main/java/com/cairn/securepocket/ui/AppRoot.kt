package com.cairn.securepocket.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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

@Composable
fun SecurePocketTheme(content: @Composable () -> Unit) = WhisperTheme(content)

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
    val battery = (health["Battery"] as? Response.Battery)?.pct
    Row(
        modifier = Modifier.fillMaxWidth().background(Obsidian).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("cairn", style = LabelSm.copy(color = OpticalWhite, letterSpacing = 0.08.em))
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (conn.toString() == "READY") Emerald else SlateText),
        )
        Spacer(Modifier.width(6.dp))
        Text(conn.toString().lowercase(), style = LabelSm.copy(color = SlateText))
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
        modifier = Modifier.fillMaxWidth().background(Obsidian).border(1.dp, Hairline).padding(horizontal = 28.dp, vertical = 10.dp),
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
                    .padding(horizontal = 18.dp, vertical = 6.dp),
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
    val lastCapture by Graph.runner.lastCapture.collectAsState()
    val sim = Graph.transport is com.cairn.securepocket.transport.Simulator

    val wifi = health["WifiStatus"] as? Response.WifiStatus
    val fw = health["FW"] as? Response.VersionInfo
    val battery = (health["Battery"] as? Response.Battery)?.pct?.takeIf { it >= 0 }
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
                Text("DEVICE NODE", style = LabelCaps.copy(color = SlateText))
                Spacer(Modifier.height(2.dp))
                Text("Pocket PKT-01", style = MaterialTheme.typography.headlineSmall, color = OpticalWhite)
                if (sim) {
                    Spacer(Modifier.height(4.dp))
                    Text("SIMULATOR DATA — not a live device", style = LabelSm.copy(color = Color(0xFFFFD9A0)))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (battery != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$battery%", style = LabelSm.copy(color = Emerald, fontWeight = FontWeight.Medium))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.BatteryStd, null, tint = Emerald, modifier = Modifier.size(14.dp))
                    }
                }
                val fwRaw = (health["BUILD"] as? Response.VersionInfo)?.raw
                    ?: fw?.raw?.let { "v$it" }
                if (fwRaw != null) Text(fwRaw, style = LabelSm.copy(color = SlateText))
            }
        }

        // Record block (replaces lock ring + air-gap prose, per Matt 2026-11-19)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (recording) "REC ACTIVE" else "STANDBY",
                style = LabelCaps.copy(color = if (recording) Crimson else SlateText),
            )
            if (recording) {
                Text(
                    "%02d:%02d".format(seconds / 60, seconds % 60),
                    fontFamily = JetBrainsMono, fontSize = 44.sp, color = OpticalWhite, letterSpacing = (-0.03).em,
                )
            } else if (lastCapture != null) {
                Text(lastCapture ?: "", style = LabelSm.copy(color = Emerald), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Waveform(active = recording)
            Text(String.format("%,d B", bytes), style = LabelSm.copy(color = SlateText))
            if (!recording) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .border(1.dp, HairlineStrong, CircleShape)
                        .clickable {
                            Graph.runner.submit(Intent.Send("APP&REC&SECEN"))
                            Graph.runner.submit(Intent.Send("APP&STA"))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Crimson))
                }
                Text("tap to record · commits to local vault", style = LabelSm.copy(color = SlateText))
            } else {
                Surface(
                    onClick = { Graph.runner.submit(Intent.Send("APP&STO")) },
                    shape = RoundedCornerShape(28.dp),
                    color = OpticalWhite,
                ) {
                    Row(
                        Modifier.padding(horizontal = 36.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(Color(0xFF2F3132)))
                        Spacer(Modifier.width(12.dp))
                        Text("Stop Recording", color = Color(0xFF2F3132), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text("tap to commit & verify", style = LabelSm.copy(color = SlateText))
            }
        }

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
                        isolated -> "AIR-GAPPED · NETS 0 · AUDIO STAYS ON HARDWARE"
                        wifi == null -> "STATE UNKNOWN — TAP SYNC"
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
            StatCard("BATTERY", battery?.let { "$it%" } ?: "—", "reserve est. lands with v1", Modifier.weight(1f))
            StatCard(
                "INTERNAL FLASH",
                space?.let { "${it.freeRaw}" } ?: "—",
                space?.let { "of ${it.totalRaw} raw" } ?: "not polled",
                Modifier.weight(1f),
            )
        }

        Text("connection: $conn", style = LabelSm.copy(color = SlateText))
        PrimaryPill("Sync Device", Modifier.fillMaxWidth()) { Graph.runner.syncNow() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostPill("Connect", Modifier.weight(1f)) { Graph.runner.submit(Intent.Connect) }
            GhostPill("Disconnect", Modifier.weight(1f)) { Graph.runner.submit(Intent.Disconnect) }
        }

        DiagnosticsDisclosure(health)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Waveform(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "phase",
    )
    val bars = remember { List(24) { i -> (5 + ((i * 37) % 23)) } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(44.dp),
    ) {
        bars.forEachIndexed { i, h ->
            val boost = if (active) 1f + phase * (0.5f + (i % 3) * 0.3f) else 0.35f
            val tint = if (active && i % 3 == 0) Emerald else if (active) OpticalWhite else SlateText
            Box(
                Modifier.width(2.dp).height((h * boost).coerceIn(3f, 34f).dp)
                    .clip(RoundedCornerShape(1.dp)).background(tint),
            )
        }
    }
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
