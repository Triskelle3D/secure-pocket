package com.cairn.securepocket.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE central for Pocket PKT01, per docs/APP_SPEC.md section 1 (wire-verified).
 * Service 001120a0-...; char a2 = WRITE (APP& cmds, zero-padded),
 * a1 = NOTIFY (responses + audio bytes), a3 = NOTIFY (events).
 * Both a1 and a3 surface as RESPONSE observations; SessionState splits.
 */
class BleTransport(context: Context, private val prefsName: String = "settings") : Transport {

    companion object {
        private const val TAG = "BleTransport"
        val SERVICE: UUID = UUID.fromString("001120a0-2233-4455-6677-889912345678")
        val CHAR_RESP: UUID = UUID.fromString("001120a1-2233-4455-6677-889912345678")
        val CHAR_CMD: UUID = UUID.fromString("001120a2-2233-4455-6677-889912345678")
        val CHAR_EVENT: UUID = UUID.fromString("001120a3-2233-4455-6677-889912345678")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME_PREFIX = "PKT01_BLUE_"
        const val MTU_REQUEST = 185
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _observations = MutableSharedFlow<Observation>(replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.SUSPEND)
    private val _events = MutableSharedFlow<TransportEvent>(replay = 1, extraBufferCapacity = 64)
    private val _state = MutableStateFlow(TransportState.IDLE)
    override val observations = _observations
    override val events = _events
    override val state: StateFlow<TransportState> = _state

    private val seq = AtomicLong(0)
    private var sessionId = "ble-unknown"
    private var gatt: BluetoothGatt? = null
    private var charCmd: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            val name = d.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith(NAME_PREFIX)) return
            stopScan()
            prefs.edit().putString("ble_mac", d.address).apply()
            connect(d)
        }
        override fun onScanFailed(errorCode: Int) {
            _state.value = TransportState.ERROR
            _events.tryEmit(TransportEvent.Error("scan failed code=$errorCode", true))
        }
    }

    override fun submit(intent: Intent) {
        when (intent) {
            is Intent.Connect -> start()
            is Intent.Disconnect -> disconnect()
            is Intent.Send -> writeCommand(intent.command)
        }
    }

    private fun start() {
        if (adapter == null || !adapter.isEnabled) {
            _state.value = TransportState.ERROR
            _events.tryEmit(TransportEvent.Error("Bluetooth off or absent", true))
            return
        }
        val mac = prefs.getString("ble_mac", null)
        if (mac != null) {
            try {
                connect(adapter.getRemoteDevice(mac))
                return
            } catch (e: Exception) { /* stale mac — fall through to scan */ }
        }
        _state.value = TransportState.CONNECTING
        _events.tryEmit(TransportEvent.StateChanged(TransportState.CONNECTING, null))
        startScan()
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT < 31 &&
            appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(TransportEvent.Error("needs location permission for BLE scan (API <31)", false))
            return
        }
        val scanner = adapter!!.bluetoothLeScanner ?: run {
            _events.tryEmit(TransportEvent.Error("no BLE scanner", false)); return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        scanning = true
        try {
            scanner.startScan(null, settings, scanCallback) // spec: filter is the NAME prefix, checked in callback
        } catch (e: SecurityException) {
            scanning = false
            _state.value = TransportState.ERROR
            _events.tryEmit(TransportEvent.Error("BLE scan permission denied", false))
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _events.tryEmit(TransportEvent.Error("connecting ${device.address}", false))
        stopScan()
        try {
            gatt = device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _state.value = TransportState.ERROR
            _events.tryEmit(TransportEvent.Error("BLE connect permission denied", false))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(MTU_REQUEST)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                charCmd = null
                _state.value = TransportState.DISCONNECTED
                _events.tryEmit(TransportEvent.StateChanged(TransportState.DISCONNECTED, sessionId))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) {
                _events.tryEmit(TransportEvent.Error("service 001120a0 not found", true))
                return
            }
            val cA1 = svc.getCharacteristic(CHAR_RESP)
            val cA3 = svc.getCharacteristic(CHAR_EVENT)
            charCmd = svc.getCharacteristic(CHAR_CMD)
            if (charCmd == null) { _events.tryEmit(TransportEvent.Error("char a2 missing", true)); return }
            enableNotify(g, cA1)
            enableNotify(g, cA3)
            sessionId = "ble-" + g.device.address.replace(":", "").takeLast(4).lowercase()
            _state.value = TransportState.READY
            _events.tryEmit(TransportEvent.SessionReset(sessionId))
            _events.tryEmit(TransportEvent.StateChanged(TransportState.READY, sessionId))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            val src = ByteSource.RESPONSE
            _observations.tryEmit(Observation(sessionId, seq.incrementAndGet(), java.time.Instant.now(), src, v))
        }

        @Deprecated("legacy write callback")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {}
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null) return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(command: String) {
        val c = charCmd
        val g = gatt
        if (c == null || g == null || mtu < 23) {
            _events.tryEmit(TransportEvent.Error("not connected; command dropped: $command", true))
            return
        }
        val raw = command.toByteArray(Charsets.US_ASCII)
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < raw.size) {
            val end = minOf(off + chunk, raw.size)
            val pad = if (end == raw.size) chunk - (end - off) else 0
            val buf = ByteArray((end - off) + pad)
            System.arraycopy(raw, off, buf, 0, end - off)
            @Suppress("DEPRECATION")
            try {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = buf
                g.writeCharacteristic(c)
            } catch (e: SecurityException) {
                _events.tryEmit(TransportEvent.Error("BLE write permission denied", false))
                return
            }
            _observations.tryEmit(Observation(sessionId, seq.incrementAndGet(), java.time.Instant.now(), ByteSource.COMMAND, buf.copyOfRange(0, end - off)))
            off = end
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        stopScan()
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        charCmd = null
        _state.value = TransportState.DISCONNECTED
        _events.tryEmit(TransportEvent.StateChanged(TransportState.DISCONNECTED, null))
    }

    override fun close() {
        stopScan()
        @SuppressLint("MissingPermission")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        charCmd = null
    }
}
