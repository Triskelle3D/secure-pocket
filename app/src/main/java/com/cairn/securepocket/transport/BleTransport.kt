package com.cairn.securepocket.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.cairn.securepocket.protocol.Commands
import com.cairn.securepocket.vault.SecurePreferences
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** All scan, GATT and timeout callbacks run on the main looper. Only one GATT operation is in flight. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(context: Context) : Transport {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val incoming = Channel<Observation>(Channel.UNLIMITED)
    private val lifecycle = Channel<TransportEvent>(Channel.UNLIMITED)
    override val observations = incoming.receiveAsFlow()
    override val events = lifecycle.receiveAsFlow()
    override val state = MutableStateFlow(TransportState.IDLE)
    val rssi = MutableStateFlow<Int?>(null)
    private var readingSignal = false
    fun readSignal() {
        handler.post {
            if (state.value == TransportState.READY && !readingSignal && !writing && writes.isEmpty()) {
                val g = link ?: return@post
                readingSignal = runCatching { g.readRemoteRssi() }.getOrDefault(false)
                if (readingSignal) handler.postDelayed({
                    if (link === g && readingSignal) {
                        readingSignal = false
                        writeNext()
                    }
                }, 3000)
            }
        }
    }
    private fun signalLost() {
        if (state.value != TransportState.READY ||
            !SecurePreferences.open(app, "settings").getBoolean("pocket_separation_alerts", false)) return
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("pocket_connection", "Pocket connection", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(app, 0, android.content.Intent(app, com.cairn.securepocket.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(413, android.app.Notification.Builder(app, "pocket_connection")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Pocket disconnected")
            .setContentText("Open Find Pocket to check its last-seen location.").setContentIntent(open).setAutoCancel(true).build())
    }

    private val serviceId = UUID.fromString(Commands.SERVICE_UUID)
    private val responseId = UUID.fromString(Commands.CH_RESPONSE_AUDIO)
    private val eventId = UUID.fromString(Commands.CH_EVENTS)
    private val writeId = UUID.fromString(Commands.CH_WRITE)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var scan: ScanCallback? = null
    private var link: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var session = ""
    private var sequence = 0L
    private val writes = ArrayDeque<ByteArray>()
    private var handshaking = false
    private var writing = false
    private var timeout: Runnable? = null
    private var closed = false

    private fun stage(value: TransportState) {
        state.value = value
        lifecycle.trySend(TransportEvent.StateChanged(value, null))
    }

    private fun deadline(message: String) {
        clearDeadline()
        timeout = Runnable { fail(message) }.also { handler.postDelayed(it, 15000) }
    }

    private fun clearDeadline() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    private fun stopScan() {
        val old = scan ?: return
        scan = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(old) }
    }

    private fun release() {
        clearDeadline()
        com.cairn.securepocket.Graph.pocketLocation.disconnected()
        stopScan()
        val old = link
        link = null
        runCatching { old?.close() }
        command = null
        mtu = 23
        handshaking = false
        readingSignal = false
        rssi.value = null
        writes.clear()
        writing = false
    }

    private fun fail(message: String) {
        release()
        state.value = TransportState.ERROR
        lifecycle.trySend(TransportEvent.Error(message, true))
    }

    override fun submit(intent: Intent) {
        handler.post {
            if (!closed) try {
                when (intent) {
                    Intent.Connect -> if (state.value in setOf(TransportState.IDLE, TransportState.ERROR, TransportState.DISCONNECTED)) start()
                    Intent.Disconnect -> { release(); stage(TransportState.DISCONNECTED) }
                    is Intent.Send -> if (state.value == TransportState.READY) {
                        val bytes = Commands.encode(intent.command)
                        val width = (mtu - 3).coerceIn(20, 512)
                        for (i in bytes.indices step width) writes.addLast(bytes.copyOfRange(i, minOf(bytes.size, i + width)))
                        writeNext()
                    }
                }
            } catch (e: SecurityException) { fail("Allow Bluetooth access in phone settings.") }
        }
    }

    private fun start() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return fail("Turn on Bluetooth and try again.")
        val known = SecurePreferences.open(app, "settings").getString("ble_mac", null)
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if (scan !== this) return
                try {
                    val r = result.scanRecord
                    val matches = result.device.address == known || r?.serviceUuids?.contains(ParcelUuid(serviceId)) == true ||
                        listOf(r?.deviceName, result.device.name).any { it?.startsWith("PKT01", true) == true }
                    if (!matches) return
                    stopScan()
                    stage(TransportState.CONNECTING)
                    deadline("Pocket stopped responding. Wake it and reconnect.")
                    link = result.device.connectGatt(app, false, callbacks, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, handler)
                    if (link == null) fail("Android could not open the Pocket connection.")
                } catch (e: SecurityException) { fail("Allow Nearby devices access and retry.") }
            }
            override fun onScanFailed(errorCode: Int) {
                if (scan === this) fail("Bluetooth scan failed ($errorCode). Wait a moment and retry.")
            }
        }
        scan = callback
        stage(TransportState.SCANNING)
        deadline("Pocket not found. Wake it, bring it nearby and disconnect the vendor app.")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== link) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { signalLost() }
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Pocket connection failed (GATT $status). Wake it and retry.")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(185)) discover(g)
                BluetoothProfile.STATE_DISCONNECTED -> { release(); stage(TransportState.DISCONNECTED) }
            }
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, value: Int, status: Int) {
            if (g !== link) return
            readingSignal = false
            if (status == BluetoothGatt.GATT_SUCCESS) rssi.value = value
            writeNext()
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (g !== link || state.value != TransportState.CONNECTING || command != null) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            discover(g)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== link) return
            if (status != 0) return fail("Pocket service discovery failed ($status).")
            val service = g.getService(serviceId) ?: return fail("Pocket recording service missing.")
            command = service.getCharacteristic(writeId) ?: return fail("Pocket command channel missing.")
            subscribe(g, eventId)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== link) return
            if (status != 0) return fail("Pocket refused notifications ($status).")
            if (d.characteristic.uuid == eventId) subscribe(g, responseId)
            else if (d.characteristic.uuid == responseId) {
                handshaking = true
                val prefs = SecurePreferences.open(app, "settings")
                val clientId = prefs.getString("pocket_client_id", null) ?: UUID.randomUUID().toString()
                    .replace("-", "").take(16).also { prefs.edit().putString("pocket_client_id", it).apply() }
                writes.addLast(Commands.encode("APP&SK&$clientId"))
                writeNext()
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { observed(g, c.uuid, it.copyOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            observed(g, c.uuid, value)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== link) return
            if (status != 0) return fail(if (handshaking) "Pocket closed the session during setup (GATT $status)." else "Pocket command failed ($status). Reconnect and retry.")
            clearDeadline()
            writing = false
            if (handshaking) deadline("Pocket did not confirm the session. Check device pairing and retry.")
            handler.postDelayed({ if (g === link) writeNext() }, 100)
        }
    }

    private fun observed(g: BluetoothGatt, id: UUID, bytes: ByteArray) {
        if (g !== link) return
        if (handshaking && id == eventId && bytes.toString(Charsets.US_ASCII).trimEnd(0.toChar()) == "MCU&SK&OK") {
            clearDeadline()
            handshaking = false
            SecurePreferences.open(app, "settings").edit().putString("ble_mac", g.device.address).apply()
            com.cairn.securepocket.Graph.pocketLocation.connected(g.device.address)
            session = UUID.randomUUID().toString()
            lifecycle.trySend(TransportEvent.SessionReset(session))
            stage(TransportState.READY)
        }
        if (state.value != TransportState.READY) return
        val source = if (id == eventId) ByteSource.EVENT else ByteSource.RESPONSE
        incoming.trySend(Observation(session, ++sequence, Instant.now(), source, bytes))
    }

    private fun discover(g: BluetoothGatt) {
        if (!g.discoverServices()) fail("Android could not discover the Pocket service.")
    }

    private fun subscribe(g: BluetoothGatt, id: UUID) {
        val c = g.getService(serviceId)?.getCharacteristic(id) ?: return fail("Pocket notification channel missing.")
        val d = c.getDescriptor(cccd) ?: return fail("Pocket notification descriptor missing.")
        if (!g.setCharacteristicNotification(c, true)) return fail("Android could not subscribe to Pocket.")
        val enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, enabled) == 0
            else { d.value = enabled; g.writeDescriptor(d) }
        if (!accepted) fail("Android refused the Pocket notification request.")
    }

    private fun writeNext() {
        if (writing || readingSignal || writes.isEmpty()) return
        val g = link ?: return
        val c = command ?: return
        val bytes = writes.removeFirst()
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writing = true
        val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, type) == 0
            else { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        if (!accepted) return fail("Android refused the Pocket command. Reconnect and retry.")
        incoming.trySend(Observation(session, ++sequence, Instant.now(), ByteSource.COMMAND, bytes))
        deadline("Pocket command timed out. Reconnect and retry.")
    }

    override fun close() {
        closed = true
        release()
        incoming.close()
        lifecycle.close()
    }
}
