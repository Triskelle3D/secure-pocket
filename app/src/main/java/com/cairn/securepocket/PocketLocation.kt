package com.cairn.securepocket

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import com.cairn.securepocket.vault.SecurePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class PocketLocation(private val context: Context) {
    data class Fix(val latitude: Double, val longitude: Double, val time: Long)

    private val prefs = SecurePreferences.open(context, "settings")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var address: String? = null
    private var job: Job? = null
    val lastSeen = MutableStateFlow(load(prefs.getString("ble_mac", null)))

    val enabled get() = prefs.getBoolean("remember_pocket_location", true)
    fun permitted(): Boolean = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun key(mac: String?) = "last_location_${mac.orEmpty().replace(":", "").lowercase()}"

    private fun load(mac: String?): Fix? = runCatching {
        val prefKey = key(mac)
        val saved = prefs.getString(prefKey, null)
        if (saved != null) return@runCatching saved.split(',').let { Fix(it[0].toDouble(), it[1].toDouble(), it[2].toLong()) }
        // Migrate the previous private plaintext location once, then remove it.
        val old = context.noBackupFilesDir.listFiles()?.firstOrNull { it.name == "pocket-last-${mac.orEmpty().replace(":", "")}.txt" }
        val lines = old?.readLines() ?: return null
        val fix = Fix(lines[0].toDouble(), lines[1].toDouble(), lines[2].toLong())
        prefs.edit().putString(prefKey, "${fix.latitude},${fix.longitude},${fix.time}").commit()
        old.delete()
        fix
    }.getOrNull()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("remember_pocket_location", value).apply()
        if (!value) {
            prefs.edit().remove(key(address ?: prefs.getString("ble_mac", null))).apply()
            lastSeen.value = null
        }
        startSampling()
    }

    fun connected(mac: String) {
        address = mac
        lastSeen.value = load(mac)
        startSampling()
    }

    fun disconnected() { address = null; job?.cancel(); job = null }

    @SuppressLint("MissingPermission")
    private fun startSampling() {
        job?.cancel()
        val mac = address ?: return
        job = scope.launch {
            val manager = context.getSystemService(LocationManager::class.java)
            while (true) {
                if (enabled && permitted()) {
                    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                        .firstOrNull { manager.isProviderEnabled(it) }
                    if (provider != null) {
                        val signal = CancellationSignal()
                        try {
                            runCatching {
                                manager.getCurrentLocation(provider, signal, context.mainExecutor) { point ->
                                    if (point != null && enabled && address == mac &&
                                        System.currentTimeMillis() - point.time in 0..60000 &&
                                        point.time >= (lastSeen.value?.time ?: 0)) {
                                        val fix = Fix(point.latitude, point.longitude, point.time)
                                        runCatching { prefs.edit().putString(key(mac), "${fix.latitude},${fix.longitude},${fix.time}").commit() }
                                            .onSuccess { lastSeen.value = fix }
                                    }
                                }
                            }
                            delay(20000)
                        } finally { signal.cancel() }
                    } else delay(20000)
                } else delay(3000)
            }
        }
    }
}
