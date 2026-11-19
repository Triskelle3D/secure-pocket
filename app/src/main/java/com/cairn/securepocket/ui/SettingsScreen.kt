package com.cairn.securepocket.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.cairn.securepocket.Graph
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.securepocket.BuildConfig
import com.cairn.securepocket.ui.theme.Crimson
import com.cairn.securepocket.ui.theme.Emerald
import com.cairn.securepocket.ui.theme.Hairline
import com.cairn.securepocket.ui.theme.JetBrainsMono
import com.cairn.securepocket.ui.theme.LabelCaps
import com.cairn.securepocket.ui.theme.LabelSm
import com.cairn.securepocket.ui.theme.OpticalWhite
import com.cairn.securepocket.ui.theme.PlusJakartaSans
import com.cairn.securepocket.ui.theme.SlateText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = remember { prefs(ctx) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("SETTINGS", style = LabelCaps.copy(color = SlateText))
        Text("Engines & storage", style = MaterialTheme.typography.displaySmall, color = OpticalWhite)
        Section("SPEECH TO TEXT", "priority · failover · local first") {
            EngineList(p, "stt", listOf(
                Eng("Local on-device Whisper", "local_whisper", "v1 — ships with the APK"),
                Eng("whisper.cpp (local)", "local_whispercpp", "runs on-device, GGML model"),
                Eng("Vosk (local)", "local_vosk", "lightweight offline models"),
                Eng("HTTP endpoint", "http_stt", "any OpenAI-compatible /transcriptions"),
                Eng("AssemblyAI", "assemblyai", "realtime + batch cloud STT"),
                Eng("Deepgram", "deepgram", "cloud STT"),
            ))
        }
        Section("TRANSCRIPT TO SUMMARY", "providers · docling layers · priority") {
            EngineList(p, "sum", listOf(
                Eng("Local LLM (on-device)", "local_llm", "v1 — quantized 3-4B model"),
                Eng("Ollama on LAN", "ollama", "host:port of your machine"),
                Eng("HTTP endpoint", "http_sum", "OpenAI-compatible /chat/completions"),
                Eng("OpenRouter", "openrouter", "multi-model cloud"),
                Eng("OpenAI", "openai", "cloud"),
                Eng("Anthropic", "anthropic", "cloud"),
            ))
            Field(p, "docling_url", "DOCLING SERVER", "http://192.168.1.10:5001")
            ToggleRow(p, "docling_pre", "PRE-DOCLING — audio → raw transcript md")
            ToggleRow(p, "docling_post", "POST-DOCLING — md → summary + transcript")
            DoclingTest(p)
        }
        Section("SINKEN & BACKUP", "where files land · the flow off the Pocket") {
            Text("Syncen = pull recordings off the Pocket, free its storage, land them somewhere durable.",
                style = MaterialTheme.typography.bodySmall, color = SlateText)
            val dest = p.getString("sync_dest", "none") ?: "none"
            Radio(p, "sync_dest", "none", "None — files stay in the vault")
            Radio(p, "sync_dest", "phone", "On-phone folder (you pick the location)")
            Radio(p, "sync_dest", "nas", "NAS on the home network")
            Radio(p, "sync_dest", "link", "Secure Pocket Link (desktop app — in flight)")
            if (dest == "phone") PhoneFolder(p)
            if (dest == "nas") {
                Field(p, "nas_host", "NAS ADDRESS", "192.168.1.50 or nas.home.lan")
                Field(p, "nas_share", "SHARE / PATH", "/volume1/pocket")
                Field(p, "nas_user", "USER", "pocket")
                Field(p, "wifi_ssid", "HOME NETWORK (SSID)", "only auto-sync on this network")
            }
            if (dest == "link") {
                Text("Desktop library server (link-server/secure_pocket_link.py) on your Mac, LAN or tailnet.",
                    style = MaterialTheme.typography.bodySmall, color = SlateText)
                Field(p, "link_base", "LINK SERVER ADDRESS", "http://192.168.1.10:8732 or http://<tailscale-ip>:8732")
                Field(p, "link_token", "LINK TOKEN (optional)", "match --token on the server")
                LinkTest(p)
            }
            if (dest != "none") ToggleRow(p, "sync_auto", "AUTO — sync when on the home network")
        }
        Section("APP", "appearance & build") {
            val ctx = LocalContext.current
            var transport by remember {
                mutableStateOf(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("transport", "sim"))
            }
            Row(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("TRANSPORT", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
                listOf("sim" to "SIMULATOR", "ble" to "BLE DEVICE").forEach { (kind, label) ->
                    val sel = transport == kind
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (sel) Emerald.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (sel) Emerald.copy(alpha = 0.6f) else Hairline, RoundedCornerShape(14.dp))
                            .clickable { Graph.switchTransport(kind); transport = kind }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(label, style = LabelCaps.copy(color = if (sel) Emerald else SlateText))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp)) {
                Text("THEME", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
                Text("OBSIDIAN WHISPER · DARK ONLY v0.1", style = LabelSm.copy(color = SlateText))
            }
            Row(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp)) {
                Text("BUILD", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
                Text("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}", style = LabelSm.copy(color = Emerald))
            }
            Row(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(vertical = 10.dp)) {
                Text("NETWORK", style = LabelCaps.copy(color = SlateText), modifier = Modifier.weight(1f))
                Text("OPT-IN — dormant until you configure a provider", style = LabelSm.copy(color = SlateText))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class Eng(val name: String, val key: String, val note: String)

@Composable
private fun Section(title: String, sub: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = LabelCaps.copy(color = SlateText))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = OpticalWhite)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SlateText,
                modifier = Modifier.graphicsLayer { rotationZ = if (open) 90f else 0f })
        }
        if (open) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun EngineList(p: android.content.SharedPreferences, section: String, engines: List<Eng>) {
    val orderKey = "${section}_order"
    val order = remember { mutableStateOf(loadOrder(p, section, engines)) }
    val setOrder: (List<String>) -> Unit = { o ->
        order.value = o
        p.edit().putString(orderKey, JSONArray(o).toString()).apply()
    }
    engines.forEachIndexed { idx, e ->
        val on = p.getBoolean("${e.key}_on", false)
        Column(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${idx + 1}", style = LabelSm.copy(color = SlateText), modifier = Modifier.padding(end = 10.dp))
                Text(e.name, style = MaterialTheme.typography.bodyMedium, color = OpticalWhite, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = { v -> p.edit().putBoolean("${e.key}_on", v).apply() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Emerald.copy(alpha = 0.5f),
                        checkedThumbColor = Emerald,
                        uncheckedTrackColor = Hairline,
                        uncheckedThumbColor = SlateText,
                    ),
                )
            }
            Text(e.note, style = LabelSm.copy(color = SlateText))
            if (on) {
                if (e.key.contains("http") || e.key == "ollama" || e.key == "local_whispercpp" || e.key == "local_vosk") {
                    Field(p, "${e.key}_url", if (e.key == "ollama") "HOST:PORT" else "ENDPOINT URL", "http://…")
                }
                if (e.key == "openai" || e.key == "anthropic" || e.key == "openrouter" || e.key == "assemblyai" || e.key == "deepgram") {
                    Field(p, "${e.key}_key", "API KEY", "paste key")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostBtn("UP") { move(order.value, engines.map { it.key }, e.key, -1, setOrder) }
                    GhostBtn("DOWN") { move(order.value, engines.map { it.key }, e.key, +1, setOrder) }
                }
            }
        }
    }
}

private fun loadOrder(p: android.content.SharedPreferences, section: String, engines: List<Eng>): List<String> {
    val raw = p.getString("${section}_order", null) ?: return engines.map { it.key }
    val keys = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
    return (keys.filter { k -> engines.any { it.key == k } } + engines.map { it.key }.filter { it !in keys })
}

private fun move(order: List<String>, all: List<String>, key: String, delta: Int, set: (List<String>) -> Unit) {
    val list = order.toMutableList()
    val i = list.indexOf(key)
    val j = i + delta
    if (i < 0 || j < 0 || j >= list.size) return
    val t = list[i]; list[i] = list[j]; list[j] = t
    set(list)
}

@Composable
private fun GhostBtn(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = LabelCaps.copy(color = OpticalWhite),
        modifier = Modifier.border(1.dp, Hairline).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Field(p: android.content.SharedPreferences, key: String, label: String, placeholder: String) {
    var text by remember { mutableStateOf(p.getString(key, "") ?: "") }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = LabelCaps.copy(color = SlateText))
        BasicTextField(
            value = text,
            onValueChange = { text = it; p.edit().putString(key, it).apply() },
            singleLine = true,
            textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = OpticalWhite),
            modifier = Modifier.fillMaxWidth().border(1.dp, Hairline).padding(horizontal = 10.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(placeholder, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = SlateText))
                inner()
            },
        )
    }
}

@Composable
private fun ToggleRow(p: android.content.SharedPreferences, key: String, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OpticalWhite, modifier = Modifier.weight(1f))
        Switch(
            checked = p.getBoolean(key, false),
            onCheckedChange = { v -> p.edit().putBoolean(key, v).apply() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Emerald.copy(alpha = 0.5f),
                checkedThumbColor = Emerald,
                uncheckedTrackColor = Hairline,
                uncheckedThumbColor = SlateText,
            ),
        )
    }
}

@Composable
private fun Radio(p: android.content.SharedPreferences, key: String, value: String, label: String) {
    val sel = p.getString(key, "none") == value
    Row(Modifier.fillMaxWidth().clickable { p.edit().putString(key, value).apply() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (sel) "●" else "○", style = LabelSm.copy(color = if (sel) Emerald else SlateText), modifier = Modifier.padding(end = 10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sel) OpticalWhite else SlateText)
    }
}

@Composable
private fun PhoneFolder(p: android.content.SharedPreferences) {
    val ctx = LocalContext.current
    var tree by remember { mutableStateOf(Uri.parse(p.getString("sync_tree", null) ?: "") .let { if (it.toString().isEmpty()) null else it }) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            tree = uri
            p.edit().putString("sync_tree", uri.toString()).apply()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tree?.lastPathSegment ?: "no folder picked", style = LabelSm.copy(color = SlateText), modifier = Modifier.weight(1f))
        GhostBtn("PICK FOLDER") { pick.launch(null) }
    }
}

@Composable
private fun LinkTest(p: android.content.SharedPreferences) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf("TAP TO PING LINK SERVER") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(msg, style = LabelSm.copy(color = SlateText), modifier = Modifier.weight(1f))
        GhostBtn("TEST") {
            var url = (p.getString("link_base", "") ?: "").trim().trimEnd('/')
            val tok = (p.getString("link_token", "") ?: "").trim()
            if (url.isEmpty()) { msg = "NO ADDRESS SET"; return@GhostBtn }
            url += "/health" + if (tok.isNotEmpty()) "?k=$tok" else ""
            msg = "PINGING…"
            scope.launch(Dispatchers.IO) {
                val code = runCatching {
                    val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    con.connectTimeout = 4000; con.readTimeout = 4000
                    con.requestMethod = "GET"
                    con.responseCode
                }.getOrDefault(-1)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    msg = if (code in 200..399) "LINK ALIVE · HTTP $code" else "LINK UNREACHABLE · HTTP $code"
                }
            }
        }
    }
}

@Composable
private fun DoclingTest(p: android.content.SharedPreferences) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf("TAP TO PING SERVER") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(msg, style = LabelSm.copy(color = SlateText), modifier = Modifier.weight(1f))
        GhostBtn("TEST") {
            val url = (p.getString("docling_url", "") ?: "").trim()
            if (url.isEmpty()) { msg = "NO URL SET"; return@GhostBtn }
            msg = "PINGING…"
            scope.launch(Dispatchers.IO) {
                val code = runCatching {
                    val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    con.connectTimeout = 4000; con.readTimeout = 4000
                    con.requestMethod = "GET"
                    con.responseCode
                }.getOrDefault(-1)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    msg = if (code in 200..399) "SERVER ALIVE · HTTP $code" else "UNREACHABLE · HTTP $code"
                }
            }
        }
    }
}
