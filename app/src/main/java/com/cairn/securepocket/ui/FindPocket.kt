package com.cairn.securepocket.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.SecurePreferences
import com.cairn.securepocket.transport.BleTransport
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@SuppressLint("MissingPermission")
@Composable
internal fun FindPocket(connected: Boolean) {
    val transport = Graph.transport as? BleTransport ?: return
    val context = LocalContext.current
    val prefs = remember { SecurePreferences.open(context, "settings") }
    val mac = prefs.getString("ble_mac", null)
    val location = Graph.pocketLocation
    val last by location.lastSeen.collectAsState()
    var allowed by remember { mutableStateOf(location.permitted()) }
    var rememberLocation by remember { mutableStateOf(location.enabled) }
    var alerts by remember { mutableStateOf(prefs.getBoolean("pocket_separation_alerts", false)) }
    var locating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Int?>(null) }
    val connectedRssi by transport.rssi.collectAsState()
    var message by remember { mutableStateOf("") }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = location.permitted()
        if (allowed) {
            rememberLocation = true
            location.setEnabled(true)
        }
    }
    val alertPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        alerts = it
        prefs.edit().putBoolean("pocket_separation_alerts", it).apply()
    }
    LaunchedEffect(connected, allowed, rememberLocation) {
        if (connected && rememberLocation && !allowed && !prefs.getBoolean("pocket_asked_location", false)) {
            prefs.edit().putBoolean("pocket_asked_location", true).apply()
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    LaunchedEffect(locating, connected) {
        while (locating && connected) {
            transport.readSignal()
            delay(1100)
        }
    }
    DisposableEffect(scanning, connected, mac) {
        val scanner = if (scanning && !connected && mac != null) runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        }.getOrNull() else null
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) { found = result.rssi }
            override fun onScanFailed(errorCode: Int) { message = "Bluetooth search failed ($errorCode)."; scanning = false }
        }
        if (scanner != null && mac != null) runCatching {
            scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(mac).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }.onFailure { message = "Allow Nearby devices and turn on Bluetooth."; scanning = false }
        else if (scanning && !connected) { message = "Turn on Bluetooth and allow Nearby devices."; scanning = false }
        onDispose { runCatching { scanner?.stopScan(callback) } }
    }
    LaunchedEffect(scanning) {
        if (scanning) { delay(20000); scanning = false; if (found == null) message = "Pocket did not advertise nearby." }
    }
    OutlinedButton(onClick = { locating = true }, modifier = Modifier.fillMaxWidth()) { Text("Find Pocket") }
    if (locating) AlertDialog(
        onDismissRequest = { locating = false; scanning = false },
        title = { Text("Find Pocket") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val signal = if (connected) connectedRssi else found
                if (signal != null) {
                    Text(if (connected || scanning) "Bluetooth signal · $signal dBm" else "Last Bluetooth reading · $signal dBm")
                    LinearProgressIndicator(progress = { ((signal + 100) / 65f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("The bar rises as the signal gets stronger.")
                }
                if (!connected) {
                    TextButton(enabled = mac != null && !scanning, onClick = { found = null; message = ""; scanning = true }) {
                        Text(if (scanning) "Searching nearby…" else "Search nearby")
                    }
                    if (mac == null) Text("Connect to your Pocket once to identify it.")
                }
                if (message.isNotBlank()) Text(message)
                if (!allowed) TextButton(onClick = {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }) { Text("Allow automatic last-seen location") }
                else TextButton(onClick = {
                    rememberLocation = !rememberLocation
                    location.setEnabled(rememberLocation)
                }) { Text(if (rememberLocation) "Stop saving and forget location" else "Save location automatically") }
                last?.let { fix ->
                    Text("Last seen at your phone’s location · ${DateFormat.getDateTimeInstance().format(Date(fix.time))}")
                    TextButton(onClick = {
                        val point = "${fix.latitude},${fix.longitude}"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point"))) }
                            .onFailure { message = "No map app available." }
                    }) { Text("Open last-seen map") }
                }
                TextButton(onClick = {
                    if (!alerts && android.os.Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        alertPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {
                        alerts = !alerts
                        prefs.edit().putBoolean("pocket_separation_alerts", alerts).apply()
                    }
                }) { Text(if (alerts) "Turn off disconnect alerts" else "Alert me when Pocket disconnects") }
            }
        },
        confirmButton = { TextButton(onClick = { scanning = false; locating = false }) { Text("Close") } },
    )
}
